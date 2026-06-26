package com.clele.parts.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.*;

/**
 * Move a quantity of stock from one location to another. The source must be owned by the current
 * user; the destination may belong to any user. Recorded as two {@code MOVE} ledger movements
 * (a negative leg at the source, a positive leg at the destination).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class StockMoveRequest {

    @NotNull(message = "Part ID is required")
    private Long partId;

    @NotNull(message = "Source location ID is required")
    private Long fromLocationId;

    @NotNull(message = "Destination location ID is required")
    private Long toLocationId;

    @NotNull(message = "Quantity is required")
    @Min(value = 1, message = "Quantity must be >= 1")
    private Integer quantity;

    /** Optional note recorded on both legs of the move. */
    private String comments;
}
