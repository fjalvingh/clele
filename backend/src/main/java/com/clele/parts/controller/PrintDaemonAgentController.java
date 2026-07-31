package com.clele.parts.controller;

import com.clele.parts.config.RequestIpUtil;
import com.clele.parts.dto.*;
import com.clele.parts.model.PrintDaemon;
import com.clele.parts.service.PrintDaemonService;
import com.clele.parts.service.PrintJobService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

/**
 * Daemon-facing endpoints, authenticated via {@code X-Daemon-Id}/{@code X-Daemon-Key} headers
 * (see {@link com.clele.parts.config.DaemonApiKeyAuthFilter}), not the session cookie.
 */
@RestController
@RequestMapping("/api/daemon")
@RequiredArgsConstructor
@Tag(name = "Print daemon agent", description = "Endpoints called by the clele-print-daemon binary")
public class PrintDaemonAgentController {

    private final PrintDaemonService printDaemonService;
    private final PrintJobService printJobService;

    @PostMapping("/register")
    @Operation(summary = "Self-register a new daemon (unauthenticated, key issued once)")
    public DaemonRegisterResponseDTO register(@RequestBody DaemonRegisterRequest request,
                                               @RequestHeader(value = "X-Daemon-Version", required = false) String version,
                                               HttpServletRequest httpRequest) {
        return printDaemonService.register(request.getHostname(), RequestIpUtil.clientIp(httpRequest), version);
    }

    @GetMapping("/jobs/next")
    @Operation(summary = "Long-poll for the next queued job (204 if none within the wait window)")
    public ResponseEntity<DaemonJobDTO> next(@RequestParam(defaultValue = "25") int wait,
                                              @RequestHeader(value = "X-Daemon-Version", required = false) String version,
                                              @RequestHeader(value = "X-Printer-Media-Kind", required = false) String mediaKind,
                                              @RequestHeader(value = "X-Printer-Media-Width", required = false) Integer mediaWidth,
                                              @RequestHeader(value = "X-Printer-Media-Length", required = false) Integer mediaLength,
                                              @RequestHeader(value = "X-Printer-Media-Name", required = false) String mediaName,
                                              HttpServletRequest httpRequest) {
        Long daemonId = currentDaemonId();
        var media = new PrintDaemonService.DaemonMediaReport(mediaKind, mediaWidth, mediaLength, mediaName);
        PrintDaemon daemon = printDaemonService.touch(daemonId, RequestIpUtil.clientIp(httpRequest), version, media);

        // The printer address is echoed on every poll — job or not — so the daemon can query the
        // printer for its media before any job exists.
        HttpHeaders headers = new HttpHeaders();
        if (daemon.getPrinterIp() != null) {
            headers.set("X-Printer-Ip", daemon.getPrinterIp());
        }
        return printJobService.nextForDaemon(daemonId, wait)
                .map(job -> new ResponseEntity<>(job, headers, HttpStatus.OK))
                .orElseGet(() -> new ResponseEntity<>(null, headers, HttpStatus.NO_CONTENT));
    }

    @PostMapping("/jobs/{id}/complete")
    @Operation(summary = "Report the outcome of a delivered job")
    public void complete(@PathVariable Long id, @RequestBody DaemonJobCompleteRequest request) {
        printJobService.complete(id, request);
    }

    private Long currentDaemonId() {
        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        return (Long) principal;
    }
}
