package com.clele.parts.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "stock_entry", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"part_id", "location_id"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StockEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "part_id", nullable = false)
    private Part part;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "location_id", nullable = false)
    private Location location;

    @Column(nullable = false)
    private Integer quantity;

    @Column(name = "minimum_quantity", nullable = false)
    private Integer minimumQuantity;

    @Column(name = "unit_price", precision = 10, scale = 2)
    private BigDecimal unitPrice;
}
