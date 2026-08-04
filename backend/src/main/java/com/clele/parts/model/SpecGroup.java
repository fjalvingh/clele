package com.clele.parts.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * A set of spec definitions covering one concept ("Power", "MCU Specs"). Replaces the fixed
 * major_type buckets: a spec belongs to exactly one group, and the groups drive the display
 * sections on the part detail screen.
 */
@Entity
@Table(name = "spec_group")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SpecGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The organisation this group belongs to. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organisation_id", nullable = false)
    private Organisation organisation;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
