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

    /** Name of the default location to create for the user. Required on create. */
    private String defaultLocationName;

    /** On update: which existing owned location to set as the user's default. */
    private Long defaultLocationId;
}
