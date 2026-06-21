package com.clele.parts.dto;

import lombok.*;

import java.util.List;

/** Result of a TEXT→NUMBER conversion scan (dry-run) or commit. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConvertToNumberResult {

    /** Number of parts that have this spec with a non-blank value. */
    private int total;

    /** How many of those parse successfully (after applying overrides). */
    private int converted;

    /** Best-effort suggested base unit (populated when the requested unit was blank). */
    private String suggestedUnit;

    /** Distinct values that still fail to parse, grouped with their occurrence count. */
    private List<Failure> failures;

    /** Updated definition, present only on a successful commit. */
    private SpecDefinitionDTO definition;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Failure {
        private String value;
        private int count;
    }
}
