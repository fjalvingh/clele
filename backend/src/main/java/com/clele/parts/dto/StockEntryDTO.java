package com.clele.parts.dto;

import lombok.*;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StockEntryDTO {
    private Long id;
    private Long partId;
    private String partName;
    private String partNumber;
    private Long locationId;
    private String locationName;
    private String locationBreadcrumb;
    private Long ownerId;
    private String ownerName;
    private Integer quantity;
    private Integer minimumQuantity;
    private boolean lowStock;
    private BigDecimal unitPrice;
}
