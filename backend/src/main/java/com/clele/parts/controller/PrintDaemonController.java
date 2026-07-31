package com.clele.parts.controller;

import com.clele.parts.config.RequestIpUtil;
import com.clele.parts.dto.PrintDaemonDTO;
import com.clele.parts.dto.PrintDaemonUpdateRequest;
import com.clele.parts.service.PrintDaemonService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Self-service management of the current user's print daemons: daemons visible here are limited
 * to those whose last-seen IP matches this request's IP (see {@link PrintDaemonService}).
 */
@RestController
@RequestMapping("/api/profile/print-daemons")
@RequiredArgsConstructor
@Tag(name = "Print daemons", description = "Self-service pairing and configuration of label-print daemons")
public class PrintDaemonController {

    private final PrintDaemonService printDaemonService;

    @GetMapping
    @Operation(summary = "List daemons visible to the current user at their current network IP")
    public List<PrintDaemonDTO> list(HttpServletRequest request) {
        return printDaemonService.listForCurrentUserAtIp(RequestIpUtil.clientIp(request));
    }

    @PostMapping("/{id}/claim")
    @Operation(summary = "Claim a pending daemon seen at the current network IP")
    public PrintDaemonDTO claim(@PathVariable Long id, HttpServletRequest request) {
        return printDaemonService.claim(id, RequestIpUtil.clientIp(request));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update a daemon's name/printer IP (owner only)")
    public PrintDaemonDTO update(@PathVariable Long id, @RequestBody PrintDaemonUpdateRequest request,
                                  HttpServletRequest httpRequest) {
        return printDaemonService.updateConfig(id, request, RequestIpUtil.clientIp(httpRequest));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Remove a daemon (owner only)")
    public void delete(@PathVariable Long id) {
        printDaemonService.delete(id);
    }
}
