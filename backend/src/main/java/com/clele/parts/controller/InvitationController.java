package com.clele.parts.controller;

import com.clele.parts.dto.EmailLookupDTO;
import com.clele.parts.dto.InvitationDTO;
import com.clele.parts.dto.InvitationRequest;
import com.clele.parts.model.Permissions;
import com.clele.parts.service.InvitationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * The inviting side: an Organisation Admin invites an email address to the organisation currently
 * in force. Every endpoint here is scoped to that organisation — inviting is the <em>only</em> way
 * an Organisation Admin brings someone in, since creating and editing accounts is
 * {@code GLOBAL_ADMIN} (the All Users screen).
 *
 * <p>The invitee's own endpoints are unauthenticated and live in {@link InvitationAccessController}.
 */
@RestController
@RequestMapping("/api/invitations")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('" + Permissions.ORG_ADMIN + "')")
@Tag(name = "Invitations", description = "Invite users to the current organisation")
public class InvitationController {

    private final InvitationService invitationService;

    @GetMapping
    @Operation(summary = "List the invitations sent for the current organisation")
    public List<InvitationDTO> listAll() {
        return invitationService.findAllForCurrentOrganisation();
    }

    @GetMapping("/lookup")
    @Operation(summary = "Look up who an email address belongs to, for the invite dialog")
    public EmailLookupDTO lookup(@RequestParam String email) {
        return invitationService.lookup(email);
    }

    @PostMapping
    @Operation(summary = "Invite an email address to the current organisation")
    public ResponseEntity<InvitationDTO> invite(@Valid @RequestBody InvitationRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(invitationService.invite(request));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Withdraw an outstanding invitation")
    public ResponseEntity<Void> revoke(@PathVariable Long id) {
        invitationService.revoke(id);
        return ResponseEntity.noContent().build();
    }
}
