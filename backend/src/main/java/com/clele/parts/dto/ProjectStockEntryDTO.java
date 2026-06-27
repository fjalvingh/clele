package com.clele.parts.dto;

import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProjectStockEntryDTO {
    private Long id;
    private Long partId;
    private String partName;
    private String partNumber;
    private Long locationId;
    private String locationName;
    private String locationBreadcrumb;
    private int quantity;
    private BigDecimal unitPrice;
    private Long movementId;
    private LocalDateTime addedAt;
    private String addedByName;
}
