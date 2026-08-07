package com.clele.parts.dto;

import lombok.Builder;
import lombok.Data;

/**
 * Whether the component cache is installed, and how old it is.
 *
 * <p>The snapshot is an optional local dataset, not part of the schema — an installation without it
 * must behave exactly as before rather than showing a step that always finds nothing. The SPA asks
 * once and hides the cache stage when {@code available} is false.
 *
 * <p>{@code snapshotDate} is the vendor's own export timestamp, and it is the honest label for the
 * stock and price figures: they were true then and nothing refreshes them.
 */
@Data
@Builder
public class ComponentCacheStatusDTO {

    private boolean available;

    /** Rows in the cache, or 0 when unavailable. */
    private long componentCount;

    /** ISO timestamp of the vendor snapshot ({@code manifest_created}), null if unknown. */
    private String snapshotDate;

    /** Where the snapshot came from ({@code cc_import_meta.source}), null if unknown. */
    private String source;
}
