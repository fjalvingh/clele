package com.clele.parts.service;

import com.clele.parts.dto.*;
import com.clele.parts.model.AppUser;
import com.clele.parts.model.DaemonCapabilities;
import com.clele.parts.model.DaemonStatus;
import com.clele.parts.model.MediaKind;
import com.clele.parts.model.PrintDaemon;
import com.clele.parts.model.PrinterType;
import com.clele.parts.repository.PrintDaemonRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

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

    /**
     * Replaces a daemon's printer configuration. The UI submits the whole form every time, so a
     * field left out is cleared rather than kept.
     */
    @Transactional
    public PrintDaemonDTO updateConfig(Long daemonId, PrintDaemonUpdateRequest request, String ip) {
        AppUser me = currentUserService.current();
        PrintDaemon daemon = requireOwned(daemonId, me);
        if (request.getName() != null && !request.getName().isBlank()) {
            daemon.setName(request.getName().trim());
        }

        PrinterType requested = PrinterType.fromName(request.getPrinterType());
        if (blankToNull(request.getPrinterType()) != null && requested == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Unknown printer type: " + request.getPrinterType());
        }
        PrinterType type = requested != null ? requested : daemon.getPrinterType();
        String printerIp = blankToNull(request.getPrinterIp());
        String queue = blankToNull(request.getPrinterQueue());
        String keyword = blankToNull(request.getMediaKeyword());

        if (type == PrinterType.DYMO_CUPS) {
            validateCupsSelection(daemon, queue, keyword);
        }

        boolean targetChanged = type != daemon.getPrinterType()
                || !Objects.equals(printerIp, daemon.getPrinterIp())
                || !Objects.equals(queue, daemon.getPrinterQueue())
                || !Objects.equals(keyword, daemon.getMediaKeyword());

        daemon.setPrinterType(type);
        daemon.setPrinterIp(printerIp);
        daemon.setPrinterQueue(queue);
        daemon.setMediaKeyword(keyword);

        // Everything detected belongs to the *old* printer. Left in place, a daemon switched from
        // one printer to the other keeps feeding the previous roll's dimensions to the label
        // renderer until its next successful poll, and prints one wrong label in the gap.
        if (targetChanged) {
            clearDetectedState(daemon);
        }
        // Capabilities are deliberately NOT cleared here. They describe the machine the daemon runs
        // on — every queue and every label size it offers — not the printer currently selected, so
        // they stay valid across a type or queue change and the pickers keep working immediately
        // instead of waiting a poll window for a re-report.
        applyMediaFromCapabilities(daemon);

        return toDto(printDaemonRepository.save(daemon));
    }

    /**
     * Records what the daemon found on its machine. Kept off the poll because the media list runs
     * to dozens of entries per queue, far too large for a response header.
     */
    @Transactional
    public void recordCapabilities(Long daemonId, DaemonCapabilities capabilities, String version) {
        PrintDaemon daemon = printDaemonRepository.findById(daemonId)
                .orElseThrow(() -> new EntityNotFoundException("Daemon not found"));
        daemon.setCapabilities(capabilities);
        daemon.setCapabilitiesAt(LocalDateTime.now());
        String reported = blankToNull(version);
        if (reported != null) {
            daemon.setVersion(reported);
        }
        // Resolve the selected label size straight away, so the UI is correct as soon as the
        // report lands rather than after the daemon's next poll.
        applyMediaFromCapabilities(daemon);
        printDaemonRepository.save(daemon);
    }

    private void validateCupsSelection(PrintDaemon daemon, String queue, String keyword) {
        DaemonCapabilities caps = daemon.getCapabilities();
        if (caps == null || queue == null) {
            return; // nothing discovered yet, or nothing selected yet — both are legitimate states
        }
        DaemonCapabilities.Queue found = findQueue(caps, queue);
        if (found == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "This daemon has no print queue called " + queue);
        }
        if (keyword != null && findMedia(found, keyword) == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Queue " + queue + " does not offer the label size " + keyword);
        }
    }

    /**
     * Fills in the media and printable area for a printer whose roll the user picked rather than
     * the printer detected. A no-op for a printer that reports its own media.
     */
    private void applyMediaFromCapabilities(PrintDaemon daemon) {
        if (daemon.getPrinterType() != PrinterType.DYMO_CUPS || daemon.getCapabilities() == null) {
            return;
        }
        DaemonCapabilities.Queue queue = findQueue(daemon.getCapabilities(), daemon.getPrinterQueue());
        if (queue == null) {
            return;
        }
        daemon.setPrinterModel(queue.makeAndModel());
        DaemonCapabilities.Media media = findMedia(queue, daemon.getMediaKeyword());
        if (media == null) {
            return;
        }
        boolean dieCut = media.lengthMm() != null && media.lengthMm().signum() > 0;
        daemon.setMediaKind(dieCut ? MediaKind.DIE_CUT : MediaKind.CONTINUOUS);
        daemon.setMediaWidthMm(media.widthMm());
        daemon.setMediaLengthMm(dieCut ? media.lengthMm() : null);
        daemon.setMediaName(media.keyword());
        daemon.setPrintableWidthMm(media.printableWidthMm());
        daemon.setPrintableLengthMm(dieCut ? media.printableLengthMm() : null);
    }

    private static DaemonCapabilities.Queue findQueue(DaemonCapabilities caps, String name) {
        if (caps == null || caps.queues() == null || name == null) {
            return null;
        }
        return caps.queues().stream().filter(q -> name.equals(q.name())).findFirst().orElse(null);
    }

    private static DaemonCapabilities.Media findMedia(DaemonCapabilities.Queue queue, String keyword) {
        if (queue == null || queue.media() == null || keyword == null) {
            return null;
        }
        return queue.media().stream().filter(m -> keyword.equals(m.keyword())).findFirst().orElse(null);
    }

    private static void clearDetectedState(PrintDaemon daemon) {
        daemon.setMediaKind(null);
        daemon.setMediaWidthMm(null);
        daemon.setMediaLengthMm(null);
        daemon.setMediaName(null);
        daemon.setPrintableWidthMm(null);
        daemon.setPrintableLengthMm(null);
        daemon.setPrinterModel(null);
    }

    @Transactional
    public void delete(Long daemonId) {
        AppUser me = currentUserService.current();
        PrintDaemon daemon = requireOwned(daemonId, me);
        printDaemonRepository.delete(daemon);
    }

    /**
     * Called on every daemon-facing request to keep last-seen IP/time, reported version and the
     * printer's reported geometry fresh. For a printer that senses its own roll this is how a
     * changed label roll shows up without any user action.
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
        if (media != null) {
            if (blankToNull(media.model()) != null) {
                daemon.setPrinterModel(media.model().trim());
            }
            BigDecimal width = media.width();
            if (width != null && width.signum() > 0) {
                daemon.setMediaKind(media.dieCut() ? MediaKind.DIE_CUT : MediaKind.CONTINUOUS);
                daemon.setMediaWidthMm(width);
                daemon.setMediaLengthMm(media.dieCut() ? media.length() : null);
                daemon.setMediaName(blankToNull(media.name()));
            }
            BigDecimal printableWidth = media.printableWidth();
            if (printableWidth != null && printableWidth.signum() > 0) {
                daemon.setPrintableWidthMm(printableWidth);
                daemon.setPrintableLengthMm(media.printableLength());
            }
        }
        return printDaemonRepository.save(daemon);
    }

    /**
     * What a daemon reported about its printer on a poll, as raw header strings.
     *
     * <p>Deliberately strings rather than parsed numbers: a value Spring cannot bind rejects the
     * whole request with a 400 before {@code touch} ever runs, which would stop the daemon polling
     * altogether rather than merely losing one measurement. Measurements are also fractional —
     * Dymo stock is sized in inches, so 19.05 mm is a normal width.
     */
    public record DaemonMediaReport(String kind, String widthMm, String lengthMm, String name,
                                    String printableWidthMm, String printableLengthMm, String model) {
        public boolean dieCut() {
            return "DIE_CUT".equalsIgnoreCase(kind);
        }

        public BigDecimal width() {
            return number(widthMm);
        }

        public BigDecimal length() {
            return number(lengthMm);
        }

        public BigDecimal printableWidth() {
            return number(printableWidthMm);
        }

        public BigDecimal printableLength() {
            return number(printableLengthMm);
        }

        private static BigDecimal number(String raw) {
            if (raw == null || raw.isBlank()) {
                return null;
            }
            try {
                return new BigDecimal(raw.trim());
            } catch (NumberFormatException e) {
                return null;
            }
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
                .printerType(daemon.getPrinterType().name())
                .printerIp(daemon.getPrinterIp())
                .printerQueue(daemon.getPrinterQueue())
                .mediaKeyword(daemon.getMediaKeyword())
                .printerModel(daemon.getPrinterModel())
                .mediaKind(daemon.getMediaKind() == null ? null : daemon.getMediaKind().name())
                .mediaWidthMm(daemon.getMediaWidthMm())
                .mediaLengthMm(daemon.getMediaLengthMm())
                .mediaName(daemon.getMediaName())
                .mediaDescription(describeMedia(daemon))
                .printableWidthMm(daemon.getPrintableWidthMm())
                .printableLengthMm(daemon.getPrintableLengthMm())
                .capabilities(daemon.getCapabilities())
                .capabilitiesReportedAt(daemon.getCapabilitiesAt())
                .owned(daemon.getOwner() != null && daemon.getOwner().getId().equals(me.getId()))
                .version(daemon.getVersion())
                .expectedVersion(daemonVersionService.getExpectedVersion())
                .outdated(daemonVersionService.isOutdated(daemon.getVersion()))
                .build();
    }

    /**
     * Human-readable summary of the media, or null when there is nothing to say yet — which for a
     * printer that cannot sense its roll means the user has not picked a label size, and the UI
     * should prompt for one rather than claim the printer failed to report.
     */
    private static String describeMedia(PrintDaemon daemon) {
        if (daemon.getMediaKind() == null || daemon.getMediaWidthMm() == null) {
            return null;
        }
        if (daemon.getMediaKind() == MediaKind.DIE_CUT && daemon.getMediaLengthMm() != null) {
            return mm(daemon.getMediaWidthMm()) + " × " + mm(daemon.getMediaLengthMm()) + " mm labels";
        }
        return mm(daemon.getMediaWidthMm()) + " mm continuous tape";
    }

    /** Renders a measurement without trailing zeros, so a 62 mm roll does not read "62.00 mm". */
    private static String mm(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
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
