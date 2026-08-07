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
    /** Parts carrying fewer than PartRepository.SPARSE_SPEC_THRESHOLD spec keys. */
    private long sparseSpecCount;
    private BigDecimal totalStockValue;
    /** Per-root-location breakdown of the stock held in the current organisation. */
    private List<LocationDashboardDTO> perLocation;
}
