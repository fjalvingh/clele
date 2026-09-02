package com.clele.parts.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** A project's imported BOM: where it came from, how far the matching has got, and every line. */
@Data
@Builder
public class ProjectBomDTO {

    private Long id;
    private Long projectId;
    private String projectName;

    /** Multiplies every line's quantity into what the build actually needs. */
    private int instanceCount;

    /** True while the project is ACTIVE — a cancelled project accepts no input. */
    private boolean canApply;

    private String filename;
    private String contentType;
    private LocalDateTime importedAt;
    private String importedByName;

    /** The column mapping the last import used, so a re-upload can pre-fill it. */
    private Map<String, String> columnMapping;

    private int totalLines;
    private int matchedCount;
    private int unmatchedCount;
    private int providedCount;
    private int excludedCount;

    /** Lines whose value or footprint moved under an existing match — the review queue. */
    private int changedCount;

    private List<ProjectBomLineDTO> lines;
}
