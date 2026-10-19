package com.clele.parts.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Create an order (supplier required, optionally with the order list items to put on it) or change
 * its reference and notes (supplier only while it is a draft).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PurchaseOrderRequest {
    private Long supplierId;
    private String supplierReference;
    private String notes;
    private List<Long> itemIds;
}
