package com.clele.parts.controller;

import com.clele.parts.dto.AcceptInvitationRequest;
import com.clele.parts.dto.PublicInvitationDTO;
import com.clele.parts.service.InvitationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * The invitee's side of an invitation — reached from a link in a mail by someone who, by
 * definition, may not have an account yet, so these endpoints are <b>unauthenticated</b>
 * ({@code permitAll} in {@code SecurityConfig}). The token in the path is the only credential.
 *
 * <p>Kept apart from {@link InvitationController} for exactly the reason the All Users and Users
 * screens are kept apart: that controller is {@code ORG_ADMIN} at class level, and a public method
 * sitting inside it would be one annotation away from a mistake.
 */
@RestController
@RequestMapping("/api/invitations/token")
@RequiredArgsConstructor
@Tag(name = "Invitations", description = "Accept or refuse an invitation (public, token-based)")
public class InvitationAccessController {

    private final InvitationService invitationService;

    @GetMapping("/{token}")
    @Operation(summary = "Look up an invitation by its token")
    public PublicInvitationDTO get(@PathVariable String token) {
        return invitationService.findByToken(token);
    }

    @PostMapping("/{token}/accept")
    @Operation(summary = "Accept an invitation, creating the account if there is none yet")
    public PublicInvitationDTO accept(@PathVariable String token,
                                      @RequestBody(required = false) AcceptInvitationRequest request) {
        return invitationService.accept(token, request);
    }

    @PostMapping("/{token}/decline")
    @Operation(summary = "Refuse an invitation")
    public PublicInvitationDTO decline(@PathVariable String token) {
        return invitationService.decline(token);
    }
}
