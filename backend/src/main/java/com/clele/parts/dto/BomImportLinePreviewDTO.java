package com.clele.parts.dto;

import lombok.Builder;
import lombok.Data;

/** One line of an import preview — what the file says, and what committing would do to it. */
@Data
@Builder
public class BomImportLinePreviewDTO {

    /** ADDED / UPDATED / UNCHANGED / REMOVED. */
    private String action;

    private String designators;
    private String value;
    private String footprint;
    private String mpn;
    private String manufacturer;
    private int quantity;
    private boolean dnp;

    /** True when this line already carries a match that the merge would keep. */
    private boolean matchKept;

    /** Set when the merge would match this line automatically, or is keeping an existing match. */
    private String matchedPartNumber;

    /** The value or footprint moved while a match was already recorded — worth re-checking. */
    private boolean changed;
}
