package com.clele.parts.dto;

import lombok.*;

import java.math.BigDecimal;
import java.util.List;

/** One line of an order, with the order list items it was made from. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PurchaseOrderLineDTO {
    private Long id;
    private Long partId;
    private String partNumber;
    private String description;
    private String manufacturer;
    private String mpn;
    private String supplierPartNumber;
    /** What is ordered — after matching, what the supplier confirmed. */
    private int quantity;
    /** What the order list items asked for, summed. Differs from quantity after a supplier's rounding. */
    private int requested;
    private BigDecimal unitPrice;
    private BigDecimal totalPrice;
    private int qtyReceived;
    private int outstanding;
    /** Where receiving should put the parts by default: the location holding most of the part. */
    private Long suggestedLocationId;
    private List<OrderItemDTO> items;
}
