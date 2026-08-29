package com.clele.parts.mcp;

import java.util.Set;

/**
 * The protocol constants of the MCP endpoint, kept together because they are the part a client
 * actually negotiates against.
 */
public final class McpProtocol {

    private McpProtocol() {}

    /**
     * Protocol revisions this server can speak. They differ in ways that do not reach a tools-only
     * server (batching, elicitation, resource links), so the handshake accepts any of them and
     * echoes back what the client asked for.
     */
    public static final Set<String> SUPPORTED_VERSIONS =
            Set.of("2024-11-05", "2025-03-26", "2025-06-18");

    /** What an unrecognised client version is answered with — the newest we know. */
    public static final String LATEST_VERSION = "2025-06-18";

    /** The code name never reaches a user, and an MCP client's tool list is read by one. */
    public static final String SERVER_NAME = "sortiment";
    public static final String SERVER_TITLE = "Sortiment parts catalogue";
    public static final String SERVER_VERSION = "1.0.0";

    // JSON-RPC 2.0 error codes.
    public static final int METHOD_NOT_FOUND = -32601;
    public static final int INVALID_PARAMS = -32602;
    public static final int INTERNAL_ERROR = -32603;

    /**
     * Shown to the model once, at connection. It buys back several wasted tool calls: what the
     * catalogue is, that parametric search is the useful way in, and how a stored spec value reads.
     */
    public static final String INSTRUCTIONS = """
            Sortiment is an electronic-parts inventory: the parts someone actually owns, where they \
            are stored, how many are left, and each part's measured specifications.

            Scope: read-only. Nothing here can create, change or delete anything, and stock cannot \
            be moved. Everything is limited to the one organisation the API key was issued for.

            Finding parts:
              - search_parts with `query` is free text over part number, description, details, \
            manufacturer and the textual spec values.
              - search_parts with `spec` is the parametric search, and is the one worth reaching \
            for: each criterion is "<field>:<op>:<value>" with op one of eq, gte, gt, lte, lt, \
            contains, any. Criteria are ANDed, e.g. ["supplyvoltage:gte:3.3", "package:eq:SOT-23"].
              - <field> is a spec field's jsonName. Call list_spec_fields first; guessing a name \
            matches nothing rather than being ignored.
              - Write values the way an engineer would: 4k7, 100nF, 1e-7, 0.1uF and 100n all work, \
            parsed against the field's own unit family.

            Reading a spec value: a numeric value is stored in the field's base SI unit (farads, \
            volts, seconds), so 1E-7 is 100 nF. Each value comes with a rendered `display` string \
            beside the raw one. A value may be a range or carry min/typ/max bounds, written \
            "min..max" or "min..nominal..max" with "null" for an open end.

            A criterion asks whether a part has some value satisfying it, so a part specified \
            2..5.5 V does match supplyvoltage:eq:3.3.
            """;
}
