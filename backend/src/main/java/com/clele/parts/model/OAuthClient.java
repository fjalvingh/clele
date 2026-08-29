package com.clele.parts.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

/**
 * An OAuth client that registered itself (RFC 7591). Registration is open and unauthenticated,
 * which is what lets Claude Desktop connect to a URL and nothing else — and is safe because
 * registering grants nothing. Access comes only from a logged-in user approving the client in the
 * browser afterwards.
 */
@Entity
@Table(name = "oauth_client")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OAuthClient {

    @Id
    @Column(name = "client_id", length = 64)
    private String clientId;

    /**
     * What the client called itself. Shown on the consent screen — and therefore attacker-controlled
     * text, which the screen presents as a claim rather than a fact.
     */
    @Column(name = "client_name", length = 200)
    private String clientName;

    /** Newline-separated. Matched <em>exactly</em>; a prefix match is how open redirectors are built. */
    @Column(name = "redirect_uris", nullable = false, columnDefinition = "TEXT")
    private String redirectUris;

    @Column(name = "grant_types", nullable = false, length = 200)
    private String grantTypes;

    @Column(name = "scope", length = 200)
    private String scope;

    @Column(name = "token_endpoint_auth_method", nullable = false, length = 40)
    private String tokenEndpointAuthMethod;

    /** Null for a public client — a desktop app cannot keep a secret, and PKCE protects it instead. */
    @Column(name = "client_secret_hash", length = 100)
    private String clientSecretHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "last_used_at")
    private LocalDateTime lastUsedAt;

    public List<String> redirectUriList() {
        return Arrays.stream(redirectUris.split("\n"))
                .map(String::trim)
                .filter(uri -> !uri.isEmpty())
                .toList();
    }

    /** Whether this client holds a secret; a public one authenticates by PKCE alone. */
    public boolean isConfidential() {
        return clientSecretHash != null;
    }

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
