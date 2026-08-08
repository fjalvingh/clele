package com.clele.parts.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * One run of "Generate parts" on a {@link PartKitTemplate} — what was asked for, and what it made.
 *
 * <p>A run creates up to thirty parts and thirty stock movements at a stroke, and until this record
 * existed nothing in the catalogue said which parts came from which run. It is what makes the last
 * run <em>undoable</em>: {@link #items} name every part and movement it produced, together with the
 * stock state immediately before it, so the undo can tell an untouched run from one the world has
 * moved on from.
 */
@Entity
@Table(name = "part_kit_generation")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PartKitGeneration {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "template_id", nullable = false)
    private PartKitTemplate template;

    /**
     * Where the stock went. For display only — the undo reverses the {@code stock_movement} rows
     * themselves, which a location merge re-points and this pointer would not follow.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "location_id")
    private Location location;

    @Column(name = "quantity_per_value", nullable = false)
    private int quantityPerValue;

    @Column(name = "unit_price", precision = 10, scale = 2)
    private BigDecimal unitPrice;

    @Column(name = "parts_created", nullable = false)
    private int partsCreated;

    @Column(name = "parts_found", nullable = false)
    private int partsFound;

    @Column(name = "stock_added", nullable = false)
    private int stockAdded;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "generated_by_id", nullable = false)
    private AppUser generatedBy;

    @Column(name = "generated_at", nullable = false)
    private LocalDateTime generatedAt;

    @OneToMany(mappedBy = "generation", cascade = CascadeType.ALL, orphanRemoval = true,
            fetch = FetchType.EAGER)
    @OrderBy("displayOrder ASC")
    @Builder.Default
    private List<PartKitGenerationItem> items = new ArrayList<>();
}
