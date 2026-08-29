package com.clele.parts.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * An issued access token and the refresh token beside it, both opaque and stored as SHA-256.
 *
 * <p>Opaque rather than a JWT: only this app ever issues or reads these, so a signed token would
 * mean managing a key to tell ourselves something a primary-key lookup already answers — and a row
 * can be revoked, which a JWT cannot.
 */
@Entity
@Table(name = "oauth_token")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OAuthToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "access_token_hash", nullable = false, unique = true, length = 64)
    private String accessTokenHash;

    @Column(name = "refresh_token_hash", unique = true, length = 64)
    private String refreshTokenHash;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "client_id", nullable = false)
    private OAuthClient client;

    /** The approval this token came from, so a replayed code can revoke what it produced. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "authorization_id")
    private OAuthAuthorization authorization;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    /** Pinned at consent, for the same reason an API key pins one — see {@link McpApiKey}. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organisation_id", nullable = false)
    private Organisation organisation;

    @Column(name = "scope", length = 200)
    private String scope;

    /** What this token was issued for. Checked on every call — see {@code OAuthTokenService}. */
    @Column(name = "audience", columnDefinition = "TEXT")
    private String audience;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "refresh_expires_at")
    private LocalDateTime refreshExpiresAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "last_used_at")
    private LocalDateTime lastUsedAt;

    public boolean isLive() {
        return revokedAt == null && expiresAt.isAfter(LocalDateTime.now());
    }

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
