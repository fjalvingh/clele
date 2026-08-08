package com.clele.parts.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * A {@link PartRequest} that may additionally open the part's stock — the New Part dialog asks for
 * an amount, a location and a per-item price alongside the part's own fields, so a part that was
 * bought rather than merely catalogued does not have to be created and then stocked in two steps.
 *
 * <p>Deliberately a subclass rather than three more nullable fields on {@code PartRequest}: the
 * update path takes the base type and therefore <em>cannot</em> carry stock at all, instead of
 * carrying it and silently ignoring it. Creating stock and editing a part are different acts.
 *
 * <p>All three are optional and the whole block is skipped when {@link #quantity} is null. When it
 * is set, {@link #locationId} is required — stock exists somewhere or not at all — while the price
 * stays optional, since the amount on hand is often known when what it cost is not.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
public class PartCreateRequest extends PartRequest {

    /** Where the stock is. Required when {@link #quantity} is set, ignored otherwise. */
    private Long locationId;

    /** How many are on hand. Null (the default) creates the part with no stock entry. */
    @Min(value = 0, message = "Quantity must be >= 0")
    private Integer quantity;

    /** What one costs. Optional even when a quantity is given. */
    @DecimalMin(value = "0.0", inclusive = true, message = "Unit price must be >= 0")
    private BigDecimal unitPrice;
}
