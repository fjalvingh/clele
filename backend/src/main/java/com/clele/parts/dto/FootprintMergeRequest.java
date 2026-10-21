package com.clele.parts.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Merge one footprint into {@code targetId}: its parts move there and its names become aliases. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FootprintMergeRequest {
    @NotNull(message = "The footprint to merge into is required")
    private Long targetId;
}
