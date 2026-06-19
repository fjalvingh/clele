package com.clele.parts.dto;

import lombok.*;

import java.math.BigDecimal;
import java.util.List;

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
    /** Per-user breakdown of locations and the stock held in them. */
    private List<UserDashboardDTO> perUser;
}
