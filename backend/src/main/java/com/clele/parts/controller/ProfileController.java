package com.clele.parts.controller;

import com.clele.parts.dto.OctopartCredentialsRequest;
import com.clele.parts.dto.OrganisationDTO;
import com.clele.parts.dto.SwitchOrganisationRequest;
import com.clele.parts.dto.OctopartCredentialsStatusDTO;
import com.clele.parts.dto.PrintingPreferenceDTO;
import com.clele.parts.dto.PrintingPreferenceRequest;
import com.clele.parts.service.CurrentOrganisationService;
import com.clele.parts.service.OrganisationService;
import com.clele.parts.service.ProfileService;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * Self-service settings for the currently authenticated user (no special permission required).
 * Currently: the user's own OctoPart (Nexar) credentials.
 */
@RestController
@RequestMapping("/api/profile")
@RequiredArgsConstructor
@Tag(name = "Profile", description = "Current user's self-service settings")
public class ProfileController {

    private final ProfileService profileService;
    private final CurrentOrganisationService currentOrganisationService;
    private final OrganisationService organisationService;

    @PutMapping("/organisation")
    @Operation(summary = "Switch the organisation in force for this session")
    public OrganisationDTO switchOrganisation(@Valid @RequestBody SwitchOrganisationRequest request) {
        return organisationService.toDTO(
                currentOrganisationService.switchTo(request.getOrganisationId()));
    }

    @GetMapping("/octopart")
    @Operation(summary = "Whether the current user has OctoPart credentials set (secret never returned)")
    public OctopartCredentialsStatusDTO getOctopartCredentials() {
        return profileService.getOctopartCredentials();
    }

    @PutMapping("/octopart")
    @Operation(summary = "Set the current user's OctoPart credentials (blank secret keeps the existing one)")
    public OctopartCredentialsStatusDTO updateOctopartCredentials(
            @RequestBody OctopartCredentialsRequest request) {
        return profileService.updateOctopartCredentials(request);
    }

    @GetMapping("/printing")
    @Operation(summary = "The current user's label-printing method (browser or daemon)")
    public PrintingPreferenceDTO getPrintingPreference() {
        return profileService.getPrintingPreference();
    }

    @PutMapping("/printing")
    @Operation(summary = "Set the current user's label-printing method and preferred daemon")
    public PrintingPreferenceDTO updatePrintingPreference(@RequestBody PrintingPreferenceRequest request) {
        return profileService.updatePrintingPreference(request);
    }
}
