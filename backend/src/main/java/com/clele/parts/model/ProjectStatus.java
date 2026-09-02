package com.clele.parts.model;

/**
 * A project has exactly two phases.
 *
 * <p>The old ladder (PLANNING → BUILDING → COMPLETED) is gone: parts are allocated out of stock the
 * moment they land on the project's part list, so there is no state in which a project has a part
 * list but has not taken the parts.
 */
public enum ProjectStatus {
    /** Parts on the part list are held by the project, taken out of stock. Everything is editable. */
    ACTIVE,
    /** Read-only. Every allocation has been returned to stock; the needed quantities are kept. */
    CANCELLED
}
