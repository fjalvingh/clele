package com.clele.parts.model;

/**
 * How a BOM line came to be matched. The distinction is what lets a re-import refresh the file's
 * own fields without ever overwriting a decision the user made: a merge may replace an
 * {@link #AUTO} match, never a {@link #MANUAL} one.
 */
public enum BomMatchSource {

    /** Matched at import by an exact, unambiguous hit on part number or MPN. */
    AUTO,

    /** Confirmed by the user on the matching screen. */
    MANUAL
}
