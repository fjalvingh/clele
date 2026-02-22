package com.clele.parts.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.math.BigDecimal;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class QuickAddRequest {

    // Part fields
    @NotBlank(message = "Part number is required")
    private String partNumber;

    @NotBlank(message = "Name is required")
    private String name;

    private String description;
    private String manufacturer;
    private String datasheetUrl;
    private Map<String, Object> specs;
    private Long categoryId;

    // Stock fields
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
