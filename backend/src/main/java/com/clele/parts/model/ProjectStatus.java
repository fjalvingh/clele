package com.clele.parts.model;

public enum ProjectStatus {
    /** BOM is being defined; parts are reserved informally only. */
    PLANNING,
    /** Parts are being pulled from stock into the project. */
    BUILDING,
    /** Build finished; parts are permanently consumed. */
    COMPLETED,
    /** Project abandoned; pulled parts may have been returned to stock. */
    CANCELLED
}
