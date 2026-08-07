package com.clele.parts.dto;

import lombok.Builder;

/**
 * One specification read out of a datasheet, with the page it was read from.
 *
 * <p>The page is what makes the value checkable. Extraction is good but not infallible — a
 * datasheet covering a family prints values for parts other than this one, and an absolute maximum
 * rating sits a column away from the recommended operating value — so the confirmation UI shows the
 * page beside each value and the user can open the PDF at it. A value you cannot trace is a value
 * you cannot defend when it turns out to be wrong.
 *
 * <p>Nullable: the model is asked for a page but a value gathered from a feature list rather than a
 * table may not have one, and that is not a reason to drop the value.
 */
@Builder
public record ExtractedSpecDTO(String key, String value, Integer page) {}
