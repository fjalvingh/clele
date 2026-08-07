package com.clele.parts.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Everything the cache holds about one selected component, mapped onto this app's fields — the
 * second half of the two-call flow ({@code search} to choose, this to take).
 *
 * <p>It writes nothing. The caller decides what to keep: Quick Add pre-fills its confirm step from
 * it and the ordinary create path stores the result, and Part Detail ticks values one at a time and
 * applies them through {@code POST /parts/{id}/ai-apply}. That is the same shape every other intake
 * path has, and it is what keeps a cache hit from silently overwriting a curated value.
 *
 * <p>{@code category} is the cache's category <em>name</em> and is context only. Resolving a name to
 * one of this organisation's categories is a separate, fuzzy problem — the AI lookup does not
 * attempt it either.
 */
@Data
@Builder
public class ComponentCacheDetailDTO {

    private String lcsc;
    private String mpn;
    private String manufacturer;
    private String description;

    /** The cache's {@code package} — this app's {@code part.footprint}, e.g. {@code SOIC-8}. */
    private String footprint;

    private String category;
    private String subcategory;
    private String basicExtended;
    private String status;
    private Integer stock;

    /** Solder joints / pin count, shown as context; this app has no column for it. */
    private Integer joints;

    private BigDecimal priceQty1;
    private BigDecimal priceMin;
    private String datasheetUrl;
    private String imageUrl;
    private String productUrl;

    /** Ready to merge into {@code part.specs}: keys are canonical {@code jsonName}s. */
    private Map<String, String> specs;

    /** The same values with their provenance, for a UI that shows what came from where. */
    private List<ComponentCacheSpecDTO> attributes;

    /**
     * Attributes the cache holds but this app did not take: an absent value ({@code "-"}, {@code
     * "NaN"}), or one of the four the row already carries as a column. Reported rather than dropped
     * silently, so "why is Package not in the specs?" has an answer.
     */
    private List<String> skipped;
}
