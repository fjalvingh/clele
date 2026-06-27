package com.clele.parts.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.math.BigDecimal;

/**
 * Add or take a quantity of stock at a single location. The {@code quantity} is the (positive)
 * amount to add / remove — the service derives the signed ledger delta.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class StockAdjustRequest {

    @NotNull(message = "Part ID is required")
    private Long partId;

    @NotNull(message = "Location ID is required")
    private Long locationId;

    @NotNull(message = "Quantity is required")
    @Min(value = 1, message = "Quantity must be >= 1")
    private Integer quantity;

    /** Only meaningful when adding stock; ignored when taking. */
    @DecimalMin(value = "0.0", inclusive = true, message = "Unit price must be >= 0")
    private BigDecimal unitPrice;

    /** Optional note recorded on the resulting stock movement. */
    private String comments;
}
