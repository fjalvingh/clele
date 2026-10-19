package com.clele.parts.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * One line as it should be after an edit or after matching with the supplier. {@code partId} names
 * the line (an order has one line per part); {@code mpn}/{@code manufacturer} are written to the
 * part only where it has none yet.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PurchaseOrderLineRequest {
    @NotNull(message = "Part is required")
    private Long partId;
    @Min(value = 1, message = "Quantity must be at least 1")
    private int quantity = 1;
    @DecimalMin(value = "0", message = "Price cannot be negative")
    private BigDecimal unitPrice;
    @Size(max = 64, message = "At most 64 characters")
    private String supplierPartNumber;
    @Size(max = 128, message = "At most 128 characters")
    private String mpn;
    private String manufacturer;
}
