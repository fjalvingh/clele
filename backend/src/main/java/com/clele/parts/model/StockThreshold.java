package com.clele.parts.model;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "part_stock_threshold",
       uniqueConstraints = @UniqueConstraint(columnNames = {"part_id", "location_id"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StockThreshold {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "part_id", nullable = false)
    private Part part;

    /** Must be a root location (parent_id IS NULL). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "location_id", nullable = false)
    private Location location;

    @Column(name = "minimum_quantity", nullable = false)
    private Integer minimumQuantity;
}
