package com.clele.parts.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

/**
 * An invitation for an email address to join one organisation, with the permissions it will hold
 * there once accepted.
 *
 * <p>This is how an Organisation Admin adds people: they cannot create or edit accounts (that is
 * {@code GLOBAL_ADMIN}, on the All Users screen) and they cannot attach an existing account without
 * its owner's consent. The invitee decides — and if they have no account yet, one is created when
 * they accept.
 *
 * <p>The {@link #token} is the whole credential on the accept/decline link, followed by someone who
 * has no session: it is long, random, single-use ({@link #status}) and expires.
 */
@Entity
@Table(name = "organisation_invitation")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrganisationInvitation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String token;

    /** Normalised (trimmed, lower-cased) email the invitation was sent to. */
    @Column(nullable = false, length = 255)
    private String email;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organisation_id", nullable = false)
    private Organisation organisation;

    /** The admin who sent it; named in the mail. Null if that account was since deleted. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "invited_by_id")
    private AppUser invitedBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private InvitationStatus status = InvitationStatus.PENDING;

    /** Permissions granted in {@link #organisation} on acceptance. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "organisation_invitation_permission",
            joinColumns = @JoinColumn(name = "invitation_id"))
    @Column(name = "permission", nullable = false)
    @Builder.Default
    private Set<String> permissions = new HashSet<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "responded_at")
    private LocalDateTime respondedAt;

    /** Past its expiry — treated as no longer answerable, whatever the stored status says. */
    public boolean isExpired() {
        return expiresAt != null && expiresAt.isBefore(LocalDateTime.now());
    }

    /** Whether this invitation can still be accepted or declined. */
    public boolean isOpen() {
        return status == InvitationStatus.PENDING && !isExpired();
    }

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
