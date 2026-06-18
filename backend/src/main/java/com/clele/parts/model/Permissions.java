package com.clele.parts.model;

/**
 * Permission strings carried by a user and used as Spring Security authorities. These keys are
 * stored verbatim in {@code app_user_permission.permission}, checked via {@code @PreAuthorize}, and
 * surfaced to the frontend (which maps them to human-readable labels).
 */
public final class Permissions {

    /** Add / edit parts (and related part data: images, quick-add, categorization). */
    public static final String PARTS_EDIT = "PARTS_EDIT";

    /** Add / edit user accounts. */
    public static final String USERS_EDIT = "USERS_EDIT";

    private Permissions() {
    }
}
