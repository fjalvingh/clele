package com.clele.parts.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.*;

import java.util.HashSet;
import java.util.Set;

/** Invite an email address to the organisation currently in force. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class InvitationRequest {

    @NotBlank(message = "Email is required")
    @Email(message = "Must be a valid email address")
    private String email;

    /** Permissions the invitee gets in this organisation once they accept. */
    private Set<String> permissions = new HashSet<>();
}
