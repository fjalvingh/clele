package com.clele.parts.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.*;

import java.util.HashSet;
import java.util.Set;

/**
 * Create/update payload for a user. On create, {@code password} is required (enforced in the
 * service); on update a blank/null password leaves the existing one unchanged.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserRequest {

    @NotBlank(message = "Email is required")
    @Email(message = "Must be a valid email address")
    private String email;

    private String password;

    private String fullName;

    private String phone;

    private Set<String> permissions = new HashSet<>();

    /**
     * The organisations this user belongs to. At least one is required — locations and stock are
     * organisation-owned, so a user without one has nothing to work on.
     */
    private Set<Long> organisationIds = new HashSet<>();
}
