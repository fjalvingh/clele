package com.clele.parts.dto;

import lombok.*;

import java.math.BigDecimal;

/**
 * Stock roll-up for a single location, as shown on the Locations tree: what is held directly at the
 * location, and what is held across its whole subtree (the direct figures included).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LocationStatsDTO {
    private Long locationId;
    /** Distinct parts / on-hand quantity / stock value at this location itself. */
    private Long directParts;
    private Long directQuantity;
    private BigDecimal directStockValue;
    /** The same figures over this location and every location below it. */
    private Long totalParts;
    private Long totalQuantity;
    private BigDecimal totalStockValue;
}
