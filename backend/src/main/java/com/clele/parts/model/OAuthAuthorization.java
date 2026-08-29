package com.clele.parts.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * One authorization request, from the browser arriving at {@code /authorize} to the code being
 * exchanged for a token.
 *
 * <p>The pending request and the issued code are one row because they are one thing: the code is
 * what the row becomes once a user approves it. Keeping them together is what stops the PKCE
 * challenge, the redirect URI and the resource from drifting apart from the code they bind.
 */
@Entity
@Table(name = "oauth_authorization")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OAuthAuthorization {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Opaque handle carried in the consent URL — the browser sees this, never the code. */
    @Column(name = "request_id", nullable = false, unique = true, length = 64)
    private String requestId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "client_id", nullable = false)
    private OAuthClient client;

    @Column(name = "redirect_uri", nullable = false, columnDefinition = "TEXT")
    private String redirectUri;

    @Column(name = "scope", length = 200)
    private String scope;

    @Column(name = "state", columnDefinition = "TEXT")
    private String state;

    /** RFC 8707: what the client says the token is for. Becomes the token's audience. */
    @Column(name = "resource", columnDefinition = "TEXT")
    private String resource;

    @Column(name = "code_challenge", nullable = false, length = 200)
    private String codeChallenge;

    @Column(name = "code_challenge_method", nullable = false, length = 10)
    private String codeChallengeMethod;

    /** Set at approval — who approved, and which organisation they picked. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private AppUser user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organisation_id")
    private Organisation organisation;

    /** SHA-256 of the authorization code; null while the request is still pending. */
    @Column(name = "code_hash", length = 64)
    private String codeHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    /** A code is single-use; this is what makes the second attempt fail. */
    @Column(name = "consumed_at")
    private LocalDateTime consumedAt;

    public boolean isExpired() {
        return expiresAt.isBefore(LocalDateTime.now());
    }

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
