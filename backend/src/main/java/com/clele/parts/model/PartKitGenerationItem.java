package com.clele.parts.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/**
 * What one value of a kit produced in one {@link PartKitGeneration} — and everything the undo needs
 * to take it back.
 *
 * <p>{@link #quantityBefore} and {@link #unitPriceBefore} are the stock entry's state immediately
 * before this run touched it. They are the undo contract:
 *
 * <ul>
 *   <li>{@code quantityBefore == null} means there was no stock entry at that location at all, so
 *       undoing removes the row rather than leaving a phantom zero behind.</li>
 *   <li>The entry must <em>still</em> hold {@code quantityBefore + quantityAdded}. Anything else
 *       means stock has been consumed, moved or corrected since, and the run is no longer the last
 *       word on it — which is precisely when undoing is refused.</li>
 *   <li>{@code unitPriceBefore} restores the weighted-average cost the add recalculated. It cannot
 *       be recovered afterwards: the WAC formula is not invertible once other movements exist.</li>
 * </ul>
 *
 * <p>{@link #part} and {@link #movement} are {@code ON DELETE SET NULL} at the database. A part
 * removed by some other route leaves this row standing and visibly incomplete, which is what makes
 * the undo refuse rather than proceed against a half-vanished run.
 */
@Entity
@Table(name = "part_kit_generation_item")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PartKitGenerationItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "generation_id", nullable = false)
    private PartKitGeneration generation;

    /** The kit value this line came from, kept as text — the template's value list may since have changed. */
    @Column(name = "value", nullable = false)
    private String value;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "part_id")
    private Part part;

    /** True when this run created the part. Only those are deleted by an undo. */
    @Column(name = "part_created", nullable = false)
    private boolean partCreated;

    @Column(name = "quantity_added", nullable = false)
    private int quantityAdded;

    /** The ledger row this run wrote, or null when it added no stock. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "movement_id")
    private StockMovement movement;

    /** On-hand at that location before the run, or null when no stock entry existed. */
    @Column(name = "quantity_before")
    private Integer quantityBefore;

    @Column(name = "unit_price_before", precision = 10, scale = 2)
    private BigDecimal unitPriceBefore;
}
