package com.clele.parts.dto;

import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StockThresholdDTO {
    private Long id;
    private Long partId;
    private String partName;
    private String partNumber;
    private Long locationId;
    private String locationName;
    private Integer minimumQuantity;
    private Long totalQuantity;
    private boolean lowStock;
}
