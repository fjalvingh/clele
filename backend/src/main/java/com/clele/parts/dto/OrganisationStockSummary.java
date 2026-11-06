package com.clele.parts.dto;

import java.math.BigDecimal;

/**
 * One organisation's section of the weekly stock summary mail.
 *
 * @param organisationName the organisation as its members know it
 * @param catalogueParts   parts in the catalogue, in stock or not
 * @param stockedParts     distinct parts with something on hand
 * @param unitsOnHand      total items on hand
 * @param stockValue       value of the stock that has a price
 * @param lowStock         thresholds currently below their minimum
 * @param recentChanges    stock changes in the last 7 days; a move between locations counts once
 */
public record OrganisationStockSummary(
        String organisationName,
        long catalogueParts,
        long stockedParts,
        long unitsOnHand,
        BigDecimal stockValue,
        long lowStock,
        long recentChanges) {
}
