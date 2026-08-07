package com.clele.parts.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * The outcome of an import, whether or not it was committed.
 *
 * <p>An import is a dry run by default. Merging a revised export into a BOM that already holds
 * matching work is destructive — lines disappear, values move — so the user sees the counts and the
 * detected column mapping first and commits second. Same shape as the convert-to-number dry run.
 */
@Data
@Builder
public class BomImportPreviewDTO {

    /** False for a dry run: nothing was written. */
    private boolean committed;

    /** Role name → header name, e.g. {@code {"REFERENCES": "Reference"}}. */
    private Map<String, String> mapping;

    /** Every header in the file, so the UI can offer the columns the mapping did not claim. */
    private List<String> headers;

    /** The delimiter that was sniffed, for display ("," / ";" / "tab" / "|"). */
    private String delimiter;

    /** Things worth telling the user that are not errors — a missing quantity column, say. */
    private List<String> warnings;

    private int totalLines;
    private int added;
    private int updated;
    private int unchanged;
    private int removed;

    /** Of the updated lines, how many had their value or footprint move under an existing match. */
    private int changed;

    /** How many lines this import matched to a part on its own. */
    private int autoMatched;

    /** Every line, with what committing would do to it. Includes the REMOVED ones. */
    private List<BomImportLinePreviewDTO> lines;
}
