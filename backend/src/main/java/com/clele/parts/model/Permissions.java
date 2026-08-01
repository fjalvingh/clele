package com.clele.parts.model;

import java.util.List;
import java.util.Set;

/**
 * Permission strings carried by a user and used as Spring Security authorities. These keys are
 * checked via {@code @PreAuthorize} and surfaced to the frontend (which maps them to
 * human-readable labels).
 *
 * <p>Permissions come in two flavours:
 * <ul>
 *   <li><b>Global</b> ({@link #GLOBAL_ADMIN}) — a property of the user, stored in
 *       {@code app_user_permission}, in force whatever organisation they are in.</li>
 *   <li><b>Per-organisation</b> (everything else) — stored in
 *       {@code app_user_organisation_permission} per (user, organisation). Being allowed to edit
 *       parts in one organisation says nothing about another.</li>
 * </ul>
 *
 * <p>Because the per-organisation set depends on the organisation currently in force, the granted
 * authorities are recomputed on login and on every organisation switch — see
 * {@code PermissionService}.
 */
public final class Permissions {

    /** Add / edit parts (and related part data: images, quick-add, categorization). Per-organisation. */
    public static final String PARTS_EDIT = "PARTS_EDIT";

    /**
     * Organisation Admin: perform organisation-level administration (the Admin screen), see the
     * organisation's members, add users to it, and change those members' permissions <em>within
     * this organisation only</em>. Per-organisation.
     */
    public static final String ORG_ADMIN = "ORG_ADMIN";

    /**
     * Global Administrator: add / edit organisations and user accounts, switch into any
     * organisation (including the template), and — implicitly — hold every per-organisation
     * permission everywhere. That implication is what makes a newly created, memberless
     * organisation usable at all.
     */
    public static final String GLOBAL_ADMIN = "GLOBAL_ADMIN";

    /** Permissions that are a property of the user, not of a membership. */
    public static final Set<String> GLOBAL = Set.of(GLOBAL_ADMIN);

    /** Permissions held per (user, organisation), in the order the UI lists them. */
    public static final List<String> PER_ORGANISATION = List.of(ORG_ADMIN, PARTS_EDIT);

    public static boolean isGlobal(String permission) {
        return GLOBAL.contains(permission);
    }

    private Permissions() {
    }
}
