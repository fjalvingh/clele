package com.clele.parts.dto;

import lombok.*;

import java.time.LocalDateTime;
import java.util.Set;

/** An invitation as the inviting organisation's admins see it. The token is never exposed here. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InvitationDTO {

    private Long id;
    private String email;
    /** Full name of the existing account behind {@link #email}, if there is one. */
    private String fullName;
    private Set<String> permissions;
    private String status;
    private boolean expired;
    private String invitedByName;
    private LocalDateTime createdAt;
    private LocalDateTime expiresAt;
    private LocalDateTime respondedAt;
    /**
     * False when the mail could not be sent (no SMTP configured, or the send failed) — the admin
     * then has to pass {@link #link} on themselves, so the UI shows it.
     */
    private boolean mailSent;
    /**
     * Why the mail did not go out, phrased for the admin; null when it did. Without it the UI can
     * only guess, and guessing "no mail server configured" at a server that is configured but
     * refusing the message points the admin at the wrong thing entirely.
     */
    private String mailError;
    /** The accept/decline link, returned only in the response to creating the invitation. */
    private String link;
}
