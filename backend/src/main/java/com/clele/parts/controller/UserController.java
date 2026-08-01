package com.clele.parts.controller;

import com.clele.parts.dto.AddMemberRequest;
import com.clele.parts.dto.UserDTO;
import com.clele.parts.dto.UserRequest;
import com.clele.parts.model.Permissions;
import com.clele.parts.service.UserService;
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
 * User management, scoped to the organisation currently in force.
 *
 * <p>The split follows what a permission can sensibly govern: an <b>account</b> is a global object
 * (its email is unique across the installation), so creating, editing and deleting one is
 * {@code GLOBAL_ADMIN}. <b>Membership</b> and <b>permissions within an organisation</b> are
 * organisation-level administration, so they are {@code ORG_ADMIN} — and only ever affect the
 * organisation the caller is currently in.
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

    @PostMapping
    @Operation(summary = "Create a new user account and add it to the current organisation")
    @PreAuthorize("hasAuthority('" + Permissions.GLOBAL_ADMIN + "')")
    public ResponseEntity<UserDTO> create(@Valid @RequestBody UserRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(userService.create(request));
    }

    @PostMapping("/members")
    @Operation(summary = "Add an existing user account to the current organisation, by email")
    public UserDTO addMember(@Valid @RequestBody AddMemberRequest request) {
        return userService.addMember(request.getEmail());
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

    @PutMapping("/{id}")
    @Operation(summary = "Update a user account (name, email, phone, password)")
    @PreAuthorize("hasAuthority('" + Permissions.GLOBAL_ADMIN + "')")
    public UserDTO update(@PathVariable Long id, @Valid @RequestBody UserRequest request) {
        return userService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a user account entirely")
    @PreAuthorize("hasAuthority('" + Permissions.GLOBAL_ADMIN + "')")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        userService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
