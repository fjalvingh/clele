package com.clele.parts.dto;

import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LocationDTO {
    private Long id;
    private String name;
    private String description;
    private Long parentId;
    private String parentName;
    /** Full path from the root, e.g. "Building A > Room B > Cupboard C". */
    private String breadcrumb;
    /** Slots in this drawer; NULL when the location is not divided into slots. */
    private Integer slotCount;
    /**
     * Slots taken: the distinct parts with stock on hand here (a part used up to zero frees its
     * slot). Only filled in when {@code slotCount} is set.
     */
    private Long usedSlots;
    /** {@code slotCount - usedSlots}; negative when more kinds of parts are stored than there are slots. */
    private Long freeSlots;
}
