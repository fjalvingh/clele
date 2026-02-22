package com.clele.parts.dto;

import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DashboardDTO {
    private long totalParts;
    private long totalLocations;
    private long totalCategories;
    private long lowStockCount;
}
