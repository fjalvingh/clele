package com.clele.parts.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class StockEntryRequest {

    @NotNull(message = "Part ID is required")
    private Long partId;

    @NotNull(message = "Location ID is required")
    private Long locationId;

    @NotNull(message = "Quantity is required")
    @Min(value = 0, message = "Quantity must be >= 0")
    private Integer quantity;

    @NotNull(message = "Minimum quantity is required")
    @Min(value = 0, message = "Minimum quantity must be >= 0")
    private Integer minimumQuantity;

    @DecimalMin(value = "0.0", inclusive = true, message = "Unit price must be >= 0")
    private BigDecimal unitPrice;
}
