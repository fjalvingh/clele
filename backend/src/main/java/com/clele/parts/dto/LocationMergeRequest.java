package com.clele.parts.dto;

import jakarta.validation.constraints.NotNull;
import lombok.*;

/**
 * Merge a location into another: move all of the source location's on-hand stock into the target
 * (registered as {@code MOVE} ledger movements), then delete the source. The source must be owned
 * by the current user (or the user must be an admin); the target may belong to any user.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LocationMergeRequest {

    @NotNull(message = "Target location ID is required")
    private Long targetId;
}
