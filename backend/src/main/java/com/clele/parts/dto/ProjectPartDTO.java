package com.clele.parts.dto;

import lombok.*;

/** One line of a project's part list: what is needed, and what the project is actually holding. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProjectPartDTO {
    private Long id;
    private Long partId;
    private String partName;
    private String partNumber;
    /** How many one build instance needs. */
    private int qtyPerInstance;
    /** The whole-build need: {@link #qtyPerInstance} × the project's instance count. */
    private int totalNeeded;
    /** How many are out of stock and held by the project right now. Zero while cancelled. */
    private int qtyAllocated;
    /** {@code totalNeeded - qtyAllocated}, never negative — what stock could not supply. */
    private int shortfall;
    private String notes;
}
