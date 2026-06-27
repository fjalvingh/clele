package com.clele.parts.model;

import jakarta.persistence.*;
import lombok.*;

/** BOM line: one part needed by a project, with the quantity per build instance. */
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

    @Column(name = "qty_per_instance", nullable = false)
    private int qtyPerInstance;

    @Column(columnDefinition = "TEXT")
    private String notes;
}
