package com.clele.parts.controller;

import com.clele.parts.dto.OctopartCredentialsRequest;
import com.clele.parts.dto.OctopartCredentialsStatusDTO;
import com.clele.parts.service.ProfileService;
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
}
