package com.clele.parts.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class StockThresholdRequest {

    @NotNull(message = "Part ID is required")
    private Long partId;

    @NotNull(message = "Location ID is required")
    private Long locationId;

    @NotNull(message = "Minimum quantity is required")
    @Min(value = 0, message = "Minimum quantity must be >= 0")
    private Integer minimumQuantity;
}
