package com.clele.parts.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * What one supplier calls a part and what it last cost there. Learned from the supplier's own
 * file when an order is matched, so the next order of the part carries the number from the start.
 */
@Entity
@Table(name = "part_supplier", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"part_id", "supplier_id"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PartSupplier {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "part_id", nullable = false)
    private Part part;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supplier_id", nullable = false)
    private Supplier supplier;

    @Column(name = "supplier_part_number", length = 64)
    private String supplierPartNumber;

    @Column(name = "last_unit_price", precision = 14, scale = 5)
    private BigDecimal lastUnitPrice;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    protected void onSave() {
        updatedAt = LocalDateTime.now();
    }
}
