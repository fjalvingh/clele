package com.clele.parts.dto;

import lombok.*;

import java.util.Set;

/**
 * What the invitee sees on the accept/decline page — reached with no session at all, so it carries
 * only what the mail already told them: who invited them, to which organisation, and as what.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PublicInvitationDTO {

    private String email;
    private String organisationName;
    private String invitedByName;
    private Set<String> permissions;
    /** PENDING / ACCEPTED / DECLINED / REVOKED. */
    private String status;
    private boolean expired;
    /** Whether this invitation can still be answered. */
    private boolean open;
    /**
     * True when no account exists for this address yet, so accepting has to create one — the page
     * then asks for a name, phone number and password.
     */
    private boolean newAccount;
}
