package com.clele.parts.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** What undoing a kit generation took back. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PartKitUndoResultDTO {

    private Long generationId;
    /** Parts deleted — only those the run created. Ones it merely restocked are left standing. */
    private int partsDeleted;
    /** Parts that existed before the run and kept their place; only the added stock came off them. */
    private int partsKept;
    private int stockRemoved;
    /** The part numbers deleted, so the confirmation can say what is gone rather than how many. */
    private List<String> deletedPartNumbers;
}
