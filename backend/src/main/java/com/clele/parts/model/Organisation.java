package com.clele.parts.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * The tenant boundary. Every part, category, location, spec definition, tag and project belongs to
 * exactly one organisation, and users are members of one or more. The organisation in force for a
 * request comes from the HTTP session — see {@code CurrentOrganisationService}.
 */
@Entity
@Table(name = "organisation")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Organisation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    /**
     * Marks the blueprint organisation whose categories, spec fields and tags are copied into every
     * newly created organisation. A flag rather than a name check, because organisations are meant
     * to be renamed. Only a Global Administrator may select it.
     */
    @Column(name = "is_template", nullable = false)
    @Builder.Default
    private boolean template = false;

    /**
     * The organisation's own Anthropic API key, <b>encrypted</b> (see {@code SecretCipher}) — never
     * the key itself, because this credential bills by the call. Null means this organisation has no
     * AI: the lookups are hidden rather than charged to somebody else, which is the whole reason the
     * key lives here instead of in application.yml.
     */
    @Column(name = "ai_api_key", columnDefinition = "TEXT")
    private String aiApiKey;

    /** Last four characters of the plaintext key, so the admin screen can show which key is stored. */
    @Column(name = "ai_key_hint", length = 8)
    private String aiKeyHint;

    /** Model this organisation asks for; null means the installation default ({@code anthropic.model}). */
    @Column(name = "ai_model", length = 100)
    private String aiModel;

    /**
     * Why AI last stopped working, as an {@link AiState} name, or null while it works. Persisted so
     * "the credits ran out" can be told from "nobody set this up" — from the outside both are just a
     * lookup that returns nothing. Cleared by the next successful call or by the connection test.
     */
    @Column(name = "ai_status_code", length = 32)
    private String aiStatusCode;

    @Column(name = "ai_status_message", columnDefinition = "TEXT")
    private String aiStatusMessage;

    @Column(name = "ai_status_at")
    private LocalDateTime aiStatusAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
