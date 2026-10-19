package com.clele.parts.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/**
 * What is actually ordered of one part on one order. The order list items that asked for the part
 * point at this line; their quantities are the demand, this line's quantity is what the supplier
 * confirmed — the two differ once a minimum order quantity or a reel size has been applied.
 */
@Entity
@Table(name = "purchase_order_line", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"purchase_order_id", "part_id"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PurchaseOrderLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "purchase_order_id", nullable = false)
    private PurchaseOrder order;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "part_id", nullable = false)
    private Part part;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "unit_price", precision = 14, scale = 5)
    private BigDecimal unitPrice;

    @Column(name = "supplier_part_number", length = 64)
    private String supplierPartNumber;

    @Column(name = "qty_received", nullable = false)
    private int qtyReceived;

    /** How many are still to come. Never negative. */
    public int outstanding() {
        return Math.max(0, quantity - qtyReceived);
    }
}
