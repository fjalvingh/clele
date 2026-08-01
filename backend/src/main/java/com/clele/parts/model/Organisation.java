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
