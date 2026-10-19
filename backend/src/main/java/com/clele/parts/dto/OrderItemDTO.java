package com.clele.parts.dto;

import com.clele.parts.model.PurchaseOrderStatus;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** One entry of the order list, or one of the items an order line was made from. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderItemDTO {
    private Long id;
    private Long partId;
    private String partNumber;
    private String description;
    private String manufacturer;
    private String mpn;
    private int quantity;
    private BigDecimal unitPrice;
    /** {@code quantity × unitPrice}; null while there is no price. */
    private BigDecimal totalPrice;
    private Long projectId;
    private String projectName;
    /** The order the item was put on; null while it is on the order list. */
    private Long orderId;
    private PurchaseOrderStatus orderStatus;
    private String notes;
    private LocalDateTime createdAt;
}
