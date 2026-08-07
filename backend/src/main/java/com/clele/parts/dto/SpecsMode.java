package com.clele.parts.dto;

/**
 * How a {@link PartRequest}'s {@code specs} map combines with the specs a part already holds.
 *
 * <p>See {@code PartRequest.specsMode} for which one a client should send and why the default is
 * {@link #MERGE}.
 */
public enum SpecsMode {

    /**
     * Overlay the supplied keys onto the part's existing specs; keys not mentioned are left alone,
     * and a key whose value is null or blank is removed.
     */
    MERGE,

    /** Replace the part's specs with exactly what was supplied. Anything not sent is deleted. */
    REPLACE
}
