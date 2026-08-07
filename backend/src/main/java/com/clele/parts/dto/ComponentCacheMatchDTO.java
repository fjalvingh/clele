package com.clele.parts.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/**
 * One component-cache hit, carrying only what it takes to recognise the part in a result list.
 *
 * <p>Deliberately not the full record: the attributes are the expensive half and the user reads at
 * most a handful of rows before picking one, so they are fetched by
 * {@link ComponentCacheDetailDTO} on selection instead. {@code specCount} stands in for them, the
 * same way the AI result card reports "N specifications".
 *
 * <p>{@code stock} and {@code price} are the vendor's figures at the moment the snapshot was taken,
 * not this organisation's — they are here because they disambiguate a part number that a dozen
 * houses second-source, not because the app tracks them.
 */
@Data
@Builder
public class ComponentCacheMatchDTO {

    /** The cache's own key, and what {@code GET /api/component-cache/{lcsc}} takes. */
    private String lcsc;

    private String mpn;
    private String manufacturer;
    private String description;
    private String packageName;
    private String category;
    private String subcategory;
    private String basicExtended;
    private String status;
    private Integer stock;
    private BigDecimal priceQty1;
    private String datasheetUrl;
    private String imageUrl;
    private String productUrl;
    private int specCount;

    /** How well this matched, 0–1. Shown so a weak hit reads as a weak hit. */
    private double score;
}
