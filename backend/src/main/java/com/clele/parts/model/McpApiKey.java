package com.clele.parts.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * A credential for the read-only MCP endpoint ({@code /api/mcp}), which an AI assistant reaches
 * without a browser session. The key stands in for both halves of the context a read needs: the
 * {@link #user} it acts as (whose per-organisation permissions still apply) and the
 * {@link #organisation} it may see, pinned at creation because an MCP client has no way to switch
 * one and no screen on which to notice it was moved.
 *
 * <p>Only the BCrypt {@link #keyHash} is stored; the token is returned once, at creation. The token
 * carries this row's id in front of the secret ({@code clele_mcp_<id>_<secret>}) so a hash can be
 * looked up at all — BCrypt is not searchable.
 */
@Entity
@Table(name = "mcp_api_key")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class McpApiKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The account the key acts as. Its permissions in {@link #organisation} still gate every call. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    /** The one organisation this key can read. Chosen when the key is created; never switched. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organisation_id", nullable = false)
    private Organisation organisation;

    /** What the key is for, in the owner's words. */
    @Column(name = "name", nullable = false, length = 100)
    private String name;

    /** BCrypt hash of the secret half of the token; the token itself is never stored. */
    @Column(name = "key_hash", nullable = false, length = 100)
    private String keyHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** Last time the key authenticated a request, written at most once a minute. */
    @Column(name = "last_used_at")
    private LocalDateTime lastUsedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
