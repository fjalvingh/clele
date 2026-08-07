package com.clele.parts.dto;

import lombok.Builder;
import lombok.Data;

/** A suggested part for a BOM line, with how closely it matched. */
@Data
@Builder
public class BomCandidateDTO {

    private PartDTO part;

    /** pg_trgm similarity, 0–1, over the better of part number and MPN. Advisory only. */
    private double score;

    /** True when the term matched the part number or MPN exactly (ignoring case). */
    private boolean exact;

    /** Which of the line's fields produced this suggestion — "mpn" or "value". */
    private String matchedOn;
}
