package com.clele.parts.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/** "Generate parts": how many of each value came in the pack, and where they went. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PartKitGenerateRequest {

    @NotNull(message = "Quantity per value is required")
    @Min(value = 0, message = "Quantity must be >= 0")
    private Integer quantityPerValue;

    @NotNull(message = "Location is required")
    private Long locationId;

    @DecimalMin(value = "0.0", inclusive = true, message = "Unit price must be >= 0")
    private BigDecimal unitPrice;
}
