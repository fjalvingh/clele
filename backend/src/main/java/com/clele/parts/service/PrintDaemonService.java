package com.clele.parts.service;

import com.clele.parts.dto.*;
import com.clele.parts.model.AppUser;
import com.clele.parts.model.DaemonStatus;
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
        if (request.getTapeWidthMm() != null) {
            if (request.getTapeWidthMm() <= 0 || request.getTapeWidthMm() > 62) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tape width must be 1-62mm");
            }
            daemon.setTapeWidthMm(request.getTapeWidthMm());
        }
        return toDto(printDaemonRepository.save(daemon));
    }

    @Transactional
    public void delete(Long daemonId) {
        AppUser me = currentUserService.current();
        PrintDaemon daemon = requireOwned(daemonId, me);
        printDaemonRepository.delete(daemon);
    }

    /** Called on every daemon-facing request to keep last-seen IP/time and reported version fresh. */
    @Transactional
    public PrintDaemon touch(Long daemonId, String ip, String version) {
        PrintDaemon daemon = printDaemonRepository.findById(daemonId)
                .orElseThrow(() -> new EntityNotFoundException("Daemon not found"));
        daemon.setLastSeenIp(ip);
        daemon.setLastSeenAt(LocalDateTime.now());
        String reported = blankToNull(version);
        if (reported != null) {
            daemon.setVersion(reported);
        }
        return printDaemonRepository.save(daemon);
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
                .tapeWidthMm(daemon.getTapeWidthMm())
                .owned(daemon.getOwner() != null && daemon.getOwner().getId().equals(me.getId()))
                .version(daemon.getVersion())
                .expectedVersion(daemonVersionService.getExpectedVersion())
                .outdated(daemonVersionService.isOutdated(daemon.getVersion()))
                .build();
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
