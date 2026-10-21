package com.clele.parts.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * A package type an organisation's parts may have — "0805", "SOT-23", "SOIC-8". {@code
 * part.footprint} holds one of these names as text rather than a foreign key, so every writer keeps
 * working; {@code FootprintService.canonicalize} is what keeps the text on the list.
 */
@Entity
@Table(name = "footprint")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Footprint {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organisation_id", nullable = false)
    private Organisation organisation;

    @Column(nullable = false, length = 64)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @OneToMany(mappedBy = "footprint", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<FootprintAlias> aliases = new ArrayList<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
