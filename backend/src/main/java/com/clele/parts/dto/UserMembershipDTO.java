package com.clele.parts.dto;

import lombok.Builder;
import lombok.Data;

import java.util.Set;

/** One user's membership of one organisation, and the permissions it carries there. */
@Data
@Builder
public class UserMembershipDTO {
    private Long organisationId;
    private String organisationName;
    /** The blueprint organisation — flagged so the UI can mark it. */
    private boolean template;
    /**
     * What the user may do in this organisation. For a Global Administrator this reports the
     * permissions they hold <em>implicitly</em> (all of them), which is what actually applies.
     */
    private Set<String> permissions;
    /**
     * True when {@code permissions} comes from GLOBAL_ADMIN rather than stored grants — the UI
     * shows them as read-only, since editing them would change nothing.
     */
    private boolean implied;
}
