package com.clele.parts.dto;

import com.clele.parts.model.BomLineStatus;
import lombok.Data;

/**
 * A decision about one BOM line.
 *
 * <p>Sending a {@code partId} matches the line; sending {@code status} without one records
 * PROVIDED, EXCLUDED or a reset to UNMATCHED. Either way the line's {@code changed} flag clears —
 * the user has now looked at it.
 */
@Data
public class BomLineMatchRequest {

    /** The part to match to. Null with status MATCHED is rejected. */
    private Long partId;

    /** Omit to let the server infer MATCHED from a partId, UNMATCHED from its absence. */
    private BomLineStatus status;

    private String notes;
}
