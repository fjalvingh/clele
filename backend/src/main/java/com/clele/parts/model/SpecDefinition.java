package com.clele.parts.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "spec_definition")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SpecDefinition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Machine key used as the JSON key inside {@code part.specs}. */
    @Column(name = "json_name", nullable = false, unique = true, length = 100)
    private String jsonName;

    /** Human-readable display title. */
    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "data_type", nullable = false, length = 20)
    private String dataType;

    @Column(length = 20)
    private String unit;

    /** When true (NUMBER with a single base SI unit), display/edit the value with metric prefixes. */
    @Column(name = "metric_prefix", nullable = false)
    private boolean metricPrefix;

    @Column(columnDefinition = "TEXT")
    private String options;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    /** Grouping for display: one of DIMENSIONS, PHYSICAL, TECHNICAL. */
    @Column(name = "major_type", nullable = false, length = 20)
    private String majorType;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (majorType == null) majorType = "TECHNICAL";
    }
}
