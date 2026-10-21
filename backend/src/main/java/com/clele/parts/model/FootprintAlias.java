package com.clele.parts.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Another name a {@link Footprint} goes by at a distributor or in an EDA library — "TO-236" for
 * SOT-23, "0805 (2012 Metric)" for 0805. A spelling that differs only in case or punctuation needs
 * no alias; the match key already covers it. Unique per organisation, like the names themselves.
 */
@Entity
@Table(name = "footprint_alias")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FootprintAlias {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "footprint_id", nullable = false)
    private Footprint footprint;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organisation_id", nullable = false)
    private Organisation organisation;

    @Column(nullable = false, length = 64)
    private String alias;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
