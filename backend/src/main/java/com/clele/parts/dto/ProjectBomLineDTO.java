package com.clele.parts.dto;

import com.clele.parts.model.BomLineStatus;
import com.clele.parts.model.BomMatchSource;
import lombok.Builder;
import lombok.Data;

import java.util.Map;

/** One line of a project's imported BOM, with its match and the stock behind it. */
@Data
@Builder
public class ProjectBomLineDTO {

    private Long id;
    private int lineNo;

    // ---- what the file said ----
    private String designators;
    private String value;
    private String footprint;
    private String mpn;
    private String manufacturer;
    private String description;
    private String datasheetUrl;

    /** Per build instance. */
    private int quantity;

    private boolean dnp;

    /** Columns the mapping did not claim, kept so nothing in the uploaded file is lost. */
    private Map<String, String> extra;

    // ---- what the user concluded ----
    private BomLineStatus status;
    private BomMatchSource matchSource;
    private boolean changed;
    private String notes;

    private Long partId;
    private String partNumber;
    private String partDescription;

    /** On-hand across the whole organisation for the matched part; null when unmatched. */
    private Long onHand;

    /** {@code quantity × project.instanceCount} — what the whole build needs. */
    private int totalNeeded;
}
