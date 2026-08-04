package com.clele.parts.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.util.List;

/**
 * Fold {@code sourceIds} into {@code targetId}: every source's JSON name becomes an alias of the
 * target, part values are re-keyed onto the target's JSON name, and the sources are deleted.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MergeSpecsRequest {

    @NotNull(message = "Target spec is required")
    private Long targetId;

    @NotEmpty(message = "At least one source spec is required")
    private List<Long> sourceIds;
}
