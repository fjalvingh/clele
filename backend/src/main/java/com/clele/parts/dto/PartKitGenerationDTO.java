package com.clele.parts.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** One past run of "Generate parts" — what it did, and whether it can still be taken back. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PartKitGenerationDTO {

    private Long id;
    private LocalDateTime generatedAt;
    private String generatedByName;

    private int quantityPerValue;
    private BigDecimal unitPrice;
    private Long locationId;
    private String locationBreadcrumb;

    private int partsCreated;
    private int partsFound;
    private int stockAdded;

    /** True only for the kit's most recent run, and only while nothing it made has been touched. */
    private boolean undoable;

    /**
     * Why it cannot be undone, in the user's terms — null when it can. This is the whole point of
     * showing the history at all: a disabled button with no reason is indistinguishable from a bug.
     */
    private String undoBlockedReason;

    private List<Line> lines;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Line {
        private String value;
        /** Null when the part has since been deleted by some other route. */
        private Long partId;
        private String partNumber;
        /** True when this run created the part; false when it already existed and was restocked. */
        private boolean created;
        private int quantityAdded;
    }
}
