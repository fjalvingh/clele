package com.clele.parts.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * An alternate JSON key a spec definition is known by at one of its sources. Merging two duplicate
 * definitions keeps the loser's {@code jsonName} here, so a later update from the source that used
 * it still lands on the surviving spec. Unique per organisation, exactly like
 * {@link SpecDefinition#getJsonName()}.
 */
@Entity
@Table(name = "spec_alias")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SpecAlias {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "spec_definition_id", nullable = false)
    private SpecDefinition specDefinition;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organisation_id", nullable = false)
    private Organisation organisation;

    @Column(name = "json_name", nullable = false, length = 100)
    private String jsonName;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
