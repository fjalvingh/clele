package com.clele.parts.controller;

import com.clele.parts.dto.AiConfigDTO;
import com.clele.parts.dto.AiConfigRequest;
import com.clele.parts.dto.AiStatusDTO;
import com.clele.parts.model.Permissions;
import com.clele.parts.service.AiCredentialsService;
import com.clele.parts.service.AiPartSearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * The organisation's AI credentials: whether lookups work, and (for an organisation admin) the key
 * and model they run on.
 *
 * <p>Split by audience on purpose. {@code /status} is for anybody who might press an AI button, so
 * the screen can fall back to the free sources and say why instead of offering a button that fails;
 * it carries no key material at all. {@code /config} is the admin's side and requires
 * {@code ORG_ADMIN} — per-organisation, because the point of the feature is that a tenant manages
 * (and pays for) its own contract without a global administrator in the loop.
 */
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
@Tag(name = "AI", description = "Per-organisation AI credentials and availability")
public class AiController {

    private final AiCredentialsService aiCredentialsService;
    private final AiPartSearchService aiPartSearchService;

    @GetMapping("/status")
    @Operation(summary = "Whether AI lookups work for this organisation, and if not, why")
    public AiStatusDTO status() {
        return aiCredentialsService.status();
    }

    /**
     * Re-check a stored key against the API. Costs a fraction of a cent (one token of output) and
     * is the way back from a recorded failure: an organisation that has topped up its account gets
     * AI back without an administrator touching anything. {@code PARTS_EDIT} rather than
     * {@code ORG_ADMIN} for exactly that reason — the person who hit the wall can clear it.
     */
    @PostMapping("/status/check")
    @PreAuthorize("hasAuthority('" + Permissions.PARTS_EDIT + "')")
    @Operation(summary = "Test the stored key with a minimal call and re-report status")
    public AiStatusDTO check() {
        try {
            aiPartSearchService.probe();
        } catch (RuntimeException e) {
            // The probe records *why* it failed; the status it produces is a better answer to
            // "is AI working" than an HTTP error, and the caller shows it either way.
        }
        return aiCredentialsService.status();
    }

    @GetMapping("/config")
    @PreAuthorize("hasAuthority('" + Permissions.ORG_ADMIN + "')")
    @Operation(summary = "This organisation's AI configuration (never returns the key)")
    public AiConfigDTO config() {
        return aiCredentialsService.config();
    }

    @PutMapping("/config")
    @PreAuthorize("hasAuthority('" + Permissions.ORG_ADMIN + "')")
    @Operation(summary = "Set this organisation's Anthropic API key and model")
    public AiConfigDTO update(@RequestBody AiConfigRequest request) {
        return aiCredentialsService.update(request);
    }
}
