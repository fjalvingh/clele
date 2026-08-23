package com.clele.parts.dto;

import lombok.*;

import java.util.List;

/**
 * One page of the dashboard's "Recently Added" list.
 *
 * <p>An explicit shape rather than Spring's {@code Page}, whose JSON carries a dozen fields the SPA
 * does not use and whose serialised form is not a stable contract.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RecentPartsPageDTO {
    private List<RecentPartDTO> items;
    /** Total parts in the organisation — what the paging control counts against. */
    private long total;
    /** Zero-based page index actually served (clamped to the available range). */
    private int page;
    private int size;
}
