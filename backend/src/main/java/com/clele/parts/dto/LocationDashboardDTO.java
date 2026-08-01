package com.clele.parts.dto;

import lombok.*;

import java.math.BigDecimal;

/**
 * Per-root-location breakdown of the stock held in an organisation. Replaces the old per-user
 * breakdown, which was derived from {@code location.owner_id} — locations belong to the
 * organisation now, so the meaningful grouping is by top-level storage location.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LocationDashboardDTO {
    private Long locationId;
    private String locationName;
    /** Locations in this root's subtree, including the root itself. */
    private Long locations;
    private Long parts;
    private Long totalQuantity;
    private BigDecimal totalStockValue;
}
