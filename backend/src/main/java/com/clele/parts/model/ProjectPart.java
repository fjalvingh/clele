package com.clele.parts.model;

import jakarta.persistence.*;
import lombok.*;

/**
 * One line of a project's <b>part list</b> — a part the project needs, how many per build instance,
 * and how many it is currently holding out of stock.
 *
 * <p>Not to be confused with a line of an <i>imported BOM</i> ({@link ProjectBomLine}), which is a
 * row of an uploaded EDA export waiting to be matched to a part.
 */
@Entity
@Table(name = "project_part", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"project_id", "part_id"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProjectPart {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "part_id", nullable = false)
    private Part part;

    /** How many of this part one build instance needs. */
    @Column(name = "qty_per_instance", nullable = false)
    private int qtyPerInstance;

    /**
     * How many are currently out of stock and held by the project. Equals the sum of this
     * (project, part)'s {@link ProjectStock} batches, and is zero while the project is cancelled.
     * Below {@link #totalNeeded(int)} when stock ran short.
     */
    @Column(name = "qty_allocated", nullable = false)
    private int qtyAllocated;

    @Column(columnDefinition = "TEXT")
    private String notes;

    /** The whole-build need: the per-instance quantity times the project's instance count. */
    public int totalNeeded(int instanceCount) {
        return qtyPerInstance * instanceCount;
    }
}
