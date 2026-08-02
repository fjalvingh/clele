package com.clele.parts.controller;

import com.clele.parts.dto.UserDTO;
import com.clele.parts.dto.UserRequest;
import com.clele.parts.model.Permissions;
import com.clele.parts.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * User management, scoped to the organisation currently in force.
 *
 * <p>The split follows what a permission can sensibly govern: an <b>account</b> is a global object
 * (its email is unique across the installation), so creating, editing and deleting one lives on
 * {@code AdminUserController} and is {@code GLOBAL_ADMIN}. <b>Membership</b> and <b>permissions
 * within an organisation</b> are organisation-level administration, so they are {@code ORG_ADMIN} —
 * and only ever affect the organisation the caller is currently in.
 *
 * <p>Note what is <em>not</em> here: an Organisation Admin cannot add someone to the organisation
 * directly. They send an invitation ({@code InvitationController}) and the invitee decides — being
 * able to attach any existing account by email would let one organisation's admin conscript a user
 * of another without their knowledge.
 */
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('" + Permissions.ORG_ADMIN + "')")
@Tag(name = "Users", description = "User management within the current organisation")
public class UserController {

    private final UserService userService;

    @GetMapping
    @Operation(summary = "List the members of the current organisation")
    public List<UserDTO> listAll() {
        return userService.findAll();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a member of the current organisation by ID")
    public UserDTO getById(@PathVariable Long id) {
        return userService.findById(id);
    }

    @DeleteMapping("/members/{id}")
    @Operation(summary = "Remove a user from the current organisation (the account itself remains)")
    public ResponseEntity<Void> removeMember(@PathVariable Long id) {
        userService.removeMember(id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/permissions")
    @Operation(summary = "Set a member's permissions within the current organisation")
    public UserDTO updatePermissions(@PathVariable Long id,
                                     @RequestBody UserRequest request) {
        return userService.updatePermissionsInCurrentOrganisation(id, request.getPermissions());
    }

}
