package com.clele.parts.model;

/**
 * Where one line of an imported BOM stands. Matching a whole BOM is work the user stops and
 * resumes, so every line carries its own decision — an unmatched line is a normal state, not an
 * error.
 */
public enum BomLineStatus {

    /** No part chosen yet. The default, and the screen's work queue. */
    UNMATCHED,

    /** Resolved to a part in the catalogue ({@code part_id} is set). */
    MATCHED,

    /**
     * An uncatalogued commodity assumed to be on hand — a resistor out of the drawer. Deliberately
     * not the same as {@link #EXCLUDED}: this part <em>is</em> fitted, it is just not worth
     * cataloguing or stocking.
     */
    PROVIDED,

    /**
     * Deliberately not fitted. Set automatically for a line the file marks DNP (do not populate),
     * and settable by hand. Skipped when the BOM is applied to the project.
     */
    EXCLUDED
}
