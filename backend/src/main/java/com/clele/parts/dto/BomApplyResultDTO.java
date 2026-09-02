package com.clele.parts.dto;

import lombok.Builder;
import lombok.Data;

/** What pushing the imported BOM into the project's part list (`project_part`) did. */
@Data
@Builder
public class BomApplyResultDTO {

    /** New project_part rows. */
    private int created;

    /** Existing rows whose quantity was updated. */
    private int updated;

    /** Rows already holding the right quantity. */
    private int unchanged;

    /** BOM lines skipped because they are not matched to a part. */
    private int skippedUnmatched;

    /** Skipped as PROVIDED — assumed on hand, deliberately not tracked. */
    private int skippedProvided;

    /** Skipped as EXCLUDED — deliberately not fitted. */
    private int skippedExcluded;

    /** Part list lines that could not be allocated in full — stock ran short. */
    private int shortParts;

    /**
     * project_part rows that no BOM line accounts for — added by hand, or left over from a line
     * since removed. They are reported, never deleted: the imported BOM is not the only way parts
     * get into a project.
     */
    private int unaccountedProjectParts;
}
