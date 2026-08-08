package com.clele.parts.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** What "Generate parts" did, per value — so the user can see which parts are new. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PartKitGenerateResultDTO {

    /** The recorded run this was — what the history lists and what an undo takes back. */
    private Long generationId;
    private int partsCreated;
    private int partsFound;
    private int stockAdded;
    private List<Line> lines;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Line {
        private String value;
        private Long partId;
        private String partNumber;
        /** True when this run created the part; false when it already existed and was reused. */
        private boolean created;
        private int quantityAdded;
    }
}
