package com.clele.parts.service;

import com.clele.parts.dto.*;
import com.clele.parts.model.AppUser;
import com.clele.parts.model.DaemonStatus;
import com.clele.parts.model.MediaKind;
import com.clele.parts.model.PrintDaemon;
import com.clele.parts.repository.PrintDaemonRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;

/**
 * Self-registered print daemons: a daemon self-registers unauthenticated (PENDING, no owner),
 * becomes visible to a browser session only while its last-seen IP matches that session's current
 * IP, and is claimed by a user (ACTIVE) before it can be selected for printing. After claiming it
 * stays restricted to its owner, still gated by the same-IP check on every listing.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PrintDaemonService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final PrintDaemonRepository printDaemonRepository;
    private final CurrentUserService currentUserService;
    private final PasswordEncoder passwordEncoder;
    private final DaemonVersionService daemonVersionService;

    @Transactional
    public DaemonRegisterResponseDTO register(String hostname, String ip, String version) {
        String rawKey = generateApiKey();
        PrintDaemon daemon = PrintDaemon.builder()
                .name((hostname == null || hostname.isBlank()) ? "Unnamed daemon" : hostname.trim())
                .apiKeyHash(passwordEncoder.encode(rawKey))
                .version(blankToNull(version))
                .registeredIp(ip)
                .lastSeenIp(ip)
                .lastSeenAt(LocalDateTime.now())
                .status(DaemonStatus.PENDING)
                .build();
        daemon = printDaemonRepository.save(daemon);
        return DaemonRegisterResponseDTO.builder().daemonId(daemon.getId()).apiKey(rawKey).build();
    }

    public List<PrintDaemonDTO> listForCurrentUserAtIp(String ip) {
        AppUser me = currentUserService.current();
        return printDaemonRepository.findVisibleToUserAtIp(me.getId(), ip).stream()
                .map(this::toDto)
                .toList();
    }

    @Transactional
    public PrintDaemonDTO claim(Long daemonId, String ip) {
        AppUser me = currentUserService.current();
        PrintDaemon daemon = findVisible(daemonId, ip, me);
        if (daemon.getOwner() != null && !daemon.getOwner().getId().equals(me.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This daemon is already claimed");
        }
        daemon.setOwner(me);
        daemon.setStatus(DaemonStatus.ACTIVE);
        return toDto(printDaemonRepository.save(daemon));
    }

    @Transactional
    public PrintDaemonDTO updateConfig(Long daemonId, PrintDaemonUpdateRequest request, String ip) {
        AppUser me = currentUserService.current();
        PrintDaemon daemon = requireOwned(daemonId, me);
        if (request.getName() != null && !request.getName().isBlank()) {
            daemon.setName(request.getName().trim());
        }
        daemon.setPrinterIp(blankToNull(request.getPrinterIp()));
        return toDto(printDaemonRepository.save(daemon));
    }

    @Transactional
    public void delete(Long daemonId) {
        AppUser me = currentUserService.current();
        PrintDaemon daemon = requireOwned(daemonId, me);
        printDaemonRepository.delete(daemon);
    }

    /**
     * Called on every daemon-facing request to keep last-seen IP/time, reported version and
     * detected printer media fresh. Media is reported by the daemon from the printer itself, so a
     * changed label roll shows up here without any user action.
     */
    @Transactional
    public PrintDaemon touch(Long daemonId, String ip, String version, DaemonMediaReport media) {
        PrintDaemon daemon = printDaemonRepository.findById(daemonId)
                .orElseThrow(() -> new EntityNotFoundException("Daemon not found"));
        daemon.setLastSeenIp(ip);
        daemon.setLastSeenAt(LocalDateTime.now());
        String reported = blankToNull(version);
        if (reported != null) {
            daemon.setVersion(reported);
        }
        if (media != null && media.widthMm() != null && media.widthMm() > 0) {
            daemon.setMediaKind(media.dieCut() ? MediaKind.DIE_CUT : MediaKind.CONTINUOUS);
            daemon.setMediaWidthMm(media.widthMm());
            daemon.setMediaLengthMm(media.dieCut() ? media.lengthMm() : null);
            daemon.setMediaName(blankToNull(media.name()));
        }
        return printDaemonRepository.save(daemon);
    }

    /** Media as reported by a daemon on its poll (headers), before validation. */
    public record DaemonMediaReport(String kind, Integer widthMm, Integer lengthMm, String name) {
        public boolean dieCut() {
            return "DIE_CUT".equalsIgnoreCase(kind);
        }
    }

    PrintDaemon requireOwned(Long daemonId, AppUser owner) {
        PrintDaemon daemon = printDaemonRepository.findById(daemonId)
                .orElseThrow(() -> new EntityNotFoundException("Daemon not found"));
        if (daemon.getOwner() == null || !daemon.getOwner().getId().equals(owner.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not your daemon");
        }
        return daemon;
    }

    private PrintDaemon findVisible(Long daemonId, String ip, AppUser me) {
        PrintDaemon daemon = printDaemonRepository.findById(daemonId)
                .orElseThrow(() -> new EntityNotFoundException("Daemon not found"));
        boolean sameIp = daemon.getLastSeenIp().equals(ip);
        boolean ownedByMe = daemon.getOwner() != null && daemon.getOwner().getId().equals(me.getId());
        if (!sameIp || (daemon.getOwner() != null && !ownedByMe)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Daemon not available");
        }
        return daemon;
    }

    private PrintDaemonDTO toDto(PrintDaemon daemon) {
        AppUser me = currentUserService.current();
        return PrintDaemonDTO.builder()
                .id(daemon.getId())
                .name(daemon.getName())
                .status(daemon.getStatus().name())
                .printerIp(daemon.getPrinterIp())
                .mediaKind(daemon.getMediaKind() == null ? null : daemon.getMediaKind().name())
                .mediaWidthMm(daemon.getMediaWidthMm())
                .mediaLengthMm(daemon.getMediaLengthMm())
                .mediaName(daemon.getMediaName())
                .mediaDescription(describeMedia(daemon))
                .owned(daemon.getOwner() != null && daemon.getOwner().getId().equals(me.getId()))
                .version(daemon.getVersion())
                .expectedVersion(daemonVersionService.getExpectedVersion())
                .outdated(daemonVersionService.isOutdated(daemon.getVersion()))
                .build();
    }

    /** Human-readable summary of the detected media, or null when the daemon hasn't reported any. */
    private static String describeMedia(PrintDaemon daemon) {
        if (daemon.getMediaKind() == null || daemon.getMediaWidthMm() == null) {
            return null;
        }
        if (daemon.getMediaKind() == MediaKind.DIE_CUT && daemon.getMediaLengthMm() != null) {
            return daemon.getMediaWidthMm() + " × " + daemon.getMediaLengthMm() + " mm die-cut labels";
        }
        return daemon.getMediaWidthMm() + " mm continuous tape";
    }

    private static String generateApiKey() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String blankToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
