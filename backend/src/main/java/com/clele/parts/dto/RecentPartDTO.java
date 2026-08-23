package com.clele.parts.dto;

import lombok.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * One row of the dashboard's "Recently Added" list: a part, plus where its stock sits and how much
 * of it there is.
 *
 * <p>A part can hold stock in several locations, so {@link #locations} carries every one of them
 * (breadcrumbs, largest holding first) rather than a single name the row would have to pick
 * arbitrarily; {@link #totalQuantity} is the on-hand total across all of them. A part that was
 * merely catalogued has an empty list and a zero total.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RecentPartDTO {
    private Long id;
    private String partNumber;
    private String description;
    /** Location breadcrumbs holding this part, most stock first. Empty when nothing is stocked. */
    private List<String> locations;
    /** On-hand total across every location in the current organisation. */
    private long totalQuantity;
    private LocalDateTime createdAt;
}
