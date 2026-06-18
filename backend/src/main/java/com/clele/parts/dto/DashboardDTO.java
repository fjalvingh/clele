package com.clele.parts.dto;

import lombok.*;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DashboardDTO {
    private long totalParts;
    private long totalLocations;
    private long totalCategories;
    private long lowStockCount;
    private BigDecimal totalStockValue;
}
