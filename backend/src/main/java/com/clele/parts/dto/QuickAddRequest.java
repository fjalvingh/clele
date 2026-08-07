package com.clele.parts.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class QuickAddRequest {

    // Part fields
    @NotBlank(message = "Part number is required")
    private String partNumber;

    private String description;
    private String details;
    private String manufacturer;
    private boolean personalNumber;

    /**
     * The package/case, e.g. {@code SOIC-8}. Only the component-cache path fills it — the AI lookup
     * returns no footprint — but it is on the request rather than that path's own DTO because it is
     * an ordinary part column, and a second create path would be a second place for the rest of the
     * intake rules to drift.
     */
    private String footprint;

    private String datasheetUrl;
    private Map<String, Object> specs;
    private Long categoryId;
    private List<String> tags;

    // Stock fields
    @NotNull(message = "Location ID is required")
    private Long locationId;

    @NotNull(message = "Quantity is required")
    @Min(value = 0, message = "Quantity must be >= 0")
    private Integer quantity;

    @DecimalMin(value = "0.0", inclusive = true, message = "Unit price must be >= 0")
    private BigDecimal unitPrice;
}
