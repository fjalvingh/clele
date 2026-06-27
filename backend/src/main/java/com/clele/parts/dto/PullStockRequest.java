package com.clele.parts.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PullStockRequest {
    @NotNull(message = "Part ID is required")
    private Long partId;
    @NotNull(message = "Location ID is required")
    private Long locationId;
    @Min(value = 1, message = "Quantity must be at least 1")
    private int quantity;
    private BigDecimal unitPrice;
}
