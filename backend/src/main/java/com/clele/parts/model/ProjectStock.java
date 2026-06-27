package com.clele.parts.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** A batch of parts physically pulled from a stock location into a project. */
@Entity
@Table(name = "project_stock")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProjectStock {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "part_id", nullable = false)
    private Part part;

    /** The source location (remembered so stock can be returned here on cancel). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "location_id", nullable = false)
    private Location location;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "unit_price", precision = 10, scale = 4)
    private BigDecimal unitPrice;

    /** The stock_movement row that deducted this stock (PROJECT_OUT or PROJECT_RETURN). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "movement_id")
    private StockMovement movement;

    @Column(name = "added_at", nullable = false)
    private LocalDateTime addedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "added_by_id")
    private AppUser addedByUser;
}
