package com.clele.parts.dto;

import lombok.*;

/**
 * Accept an invitation. When the invitee has no account yet, every field is required — that is the
 * moment the account is created, and it needs a password to be usable at all. For an existing
 * account the fields are ignored: an invitation must not be able to rewrite someone's details, let
 * alone their password.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AcceptInvitationRequest {

    private String fullName;
    private String phone;
    private String password;
}
