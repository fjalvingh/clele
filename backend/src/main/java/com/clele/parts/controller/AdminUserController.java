package com.clele.parts.controller;

import com.clele.parts.dto.AdminUserDTO;
import com.clele.parts.dto.MembershipRequest;
import com.clele.parts.dto.PermissionsRequest;
import com.clele.parts.dto.UserRequest;
import com.clele.parts.model.Permissions;
import com.clele.parts.service.AdminUserService;
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
 * Installation-wide user administration ("All Users"). Every endpoint crosses organisation
 * boundaries, so the whole controller is {@code GLOBAL_ADMIN} — the class-level annotation is the
 * guarantee, and no method may weaken it.
 *
 * <p>Not merged into {@code UserController}, which is organisation-scoped ({@code ORG_ADMIN}): that
 * one exists precisely to keep an Organisation Admin inside their own organisation, and mixing the
 * two would make it far too easy to widen a method by accident.
 */
@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('" + Permissions.GLOBAL_ADMIN + "')")
@Tag(name = "All Users", description = "Installation-wide user administration (Global Administrator)")
public class AdminUserController {

    private final AdminUserService adminUserService;

    @GetMapping
    @Operation(summary = "List every user account with all of its organisation memberships")
    public List<AdminUserDTO> listAll() {
        return adminUserService.findAll();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get one user account with all of its organisation memberships")
    public AdminUserDTO getById(@PathVariable Long id) {
        return adminUserService.findById(id);
    }

    @PostMapping
    @Operation(summary = "Create a user account in one or more organisations")
    public ResponseEntity<AdminUserDTO> create(@Valid @RequestBody UserRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(adminUserService.create(request));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a user account entirely, in every organisation")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        adminUserService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update account details and global permissions")
    public AdminUserDTO update(@PathVariable Long id, @Valid @RequestBody UserRequest request) {
        return adminUserService.update(id, request);
    }

    @PostMapping("/{id}/organisations")
    @Operation(summary = "Add the user to an organisation")
    public AdminUserDTO addToOrganisation(@PathVariable Long id,
                                          @Valid @RequestBody MembershipRequest request) {
        return adminUserService.addToOrganisation(id, request.getOrganisationId());
    }

    @DeleteMapping("/{id}/organisations/{organisationId}")
    @Operation(summary = "Remove the user from an organisation")
    public AdminUserDTO removeFromOrganisation(@PathVariable Long id,
                                               @PathVariable Long organisationId) {
        return adminUserService.removeFromOrganisation(id, organisationId);
    }

    @PutMapping("/{id}/organisations/{organisationId}/permissions")
    @Operation(summary = "Set the user's permissions within one organisation")
    public AdminUserDTO setPermissions(@PathVariable Long id,
                                       @PathVariable Long organisationId,
                                       @RequestBody PermissionsRequest request) {
        return adminUserService.setPermissions(id, organisationId, request.getPermissions());
    }
}
