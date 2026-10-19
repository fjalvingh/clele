package com.clele.parts.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Create or change an order list item. {@code partId} is read on create only — an item is a wish
 * for one part; wanting another part is another item.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrderItemRequest {
    private Long partId;
    @Min(value = 1, message = "Quantity must be at least 1")
    private int quantity = 1;
    /** Leave empty on create to take the last price a supplier charged for the part. */
    @DecimalMin(value = "0", message = "Price cannot be negative")
    private BigDecimal unitPrice;
    private Long projectId;
    private String notes;
}
