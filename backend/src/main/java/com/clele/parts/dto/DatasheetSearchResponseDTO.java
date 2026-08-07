package com.clele.parts.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * The outcome of a datasheet search, not just its results.
 *
 * <p>An empty {@code results} list has two very different meanings — the web search ran and this part
 * has no findable datasheet, or it never ran because DuckDuckGo answered with a bot challenge. The UI
 * must not present the second as the first, so the status travels with the results.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DatasheetSearchResponseDTO {

    private List<DatasheetSuggestionDTO> results;

    /** Where {@code results} came from: {@code WEB}, {@code AI} or {@code NONE}. */
    private String source;

    /** {@code OK} / {@code NO_RESULTS} / {@code BLOCKED} / {@code FAILED} / {@code SKIPPED}. */
    private String webSearchStatus;

    /** Human-readable reason, when there is one worth showing ("bot challenge served as HTTP 202"). */
    private String detail;
}
