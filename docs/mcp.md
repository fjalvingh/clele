# MCP — letting an AI read the catalogue

Part of the Clele documentation — `CLAUDE.md` holds the overview and the index of these files;
`API.md` lists the REST endpoints.

**`POST /api/mcp` is a Model Context Protocol server over the catalogue**, so an assistant
(Claude Code, Claude Desktop, anything else speaking MCP) can answer "do I have a 100 nF 0805 in
stock?" against the real inventory instead of guessing. It is **read-only**, authenticated by an
API key, and scoped to exactly one organisation.

## What a client can do

Six tools, all of them questions:

| tool | answers |
|---|---|
| `search_parts` | free-text and **parametric** search (`spec: ["supplyvoltage:gte:3.3"]`), plus category / manufacturer / tag / in-stock filters |
| `get_part` | one part in full: every spec (raw *and* rendered), stock per location, tags, datasheet |
| `list_spec_fields` | the spec fields and their `jsonName` — what `search_parts`' `spec` filter expects |
| `list_categories` | the category tree, flattened, with part counts |
| `list_locations` | the storage locations, flattened |
| `list_low_stock` | parts under their minimum — what needs reordering |

Nothing writes. There is no tool to create a part, edit a spec or move stock, which is the point:
a key handed to an assistant cannot be talked into changing the inventory.

## Connecting a client

Issue a key in **My Account → AI access (MCP)**; the token is shown once. Then:

```
claude mcp add --transport http sortiment https://your-host/api/mcp --header "X-Api-Key: clele_mcp_…"
```

`Authorization: Bearer <token>` works too, for clients that only send that. The Profile screen
prints the whole command with the token already in it.

## The key

`mcp_api_key` (V57) — owner, organisation, name, BCrypt hash, `created_at`, `last_used_at`.
Managed at `/api/profile/mcp-keys` (session-authenticated, self-service: a key carries no more
access than its owner already has). **`/api/mcp` itself is on its own security chain
(`SecurityConfig.mcpSecurityFilterChain`), scoped to that one path** — so a key can read the
catalogue but cannot reach key management and mint another one.

- **The token is `clele_mcp_<id>_<secret>`.** The id in front is not decoration: only a BCrypt hash
  of the secret is stored and a hash cannot be looked up, so the token has to say which row to
  compare against. Same shape as the print daemon's `X-Daemon-Id` + `X-Daemon-Key`, folded into one
  value because MCP clients configure a single header.
- **The organisation is pinned on the key**, not resolved the session's way. An MCP client has no
  way to switch organisation and no screen on which to notice it was moved, so a key that followed
  `app_user.last_organisation_id` would quietly start answering about a different catalogue.
  `McpApiKeyAuthFilter` sets it as a request attribute that `CurrentOrganisationService.current()`
  honours ahead of the session — see `PINNED_ORGANISATION_ATTRIBUTE`.
- **Membership is re-checked on every call.** A key pins an organisation at creation, but a
  membership can be revoked afterwards, and a stored credential must not outlive the access it was
  granted under. Authorities are recomputed from the database the same way
  `OrganisationAuthoritiesFilter` does for a session.
- `last_used_at` is written at most once a minute — otherwise every call to a read-only endpoint
  would be a write.

## The protocol layer

`mcp/McpController` is a JSON-RPC 2.0 dispatcher over four methods: `initialize`, `ping`,
`tools/list`, `tools/call`. That is the whole server side of the "Streamable HTTP" transport for a
server that never pushes anything.

- **Hand-rolled, not an SDK.** The MCP Java SDK brings a reactive stack and a Spring Boot version
  this project is not on, for a dispatcher that fits in a page. The protocol facts that matter are
  pinned in `McpProtocol`.
- **No SSE, no sessions.** A `GET` (the client asking for a server-initiated stream) is answered
  405, which the specification allows for a server with nothing to push; every response is a single
  `application/json` body. Nothing is remembered between calls, so no `Mcp-Session-Id` is issued.
- **The protocol version is negotiated, not asserted**: a version in `SUPPORTED_VERSIONS` is echoed
  back, anything else is answered with the newest we know and the client decides.
- A notification (no `id`) gets no reply at all, not even an error — hence the 202.
- **A failing tool comes back as a tool result marked `isError`**, not as a transport error. That is
  the MCP convention and it is the useful one: the model sees what went wrong and fixes its own
  arguments, where a JSON-RPC error just ends the exchange. A protocol mistake — an unknown tool
  name — is still a JSON-RPC error.

## Shaping answers for a model

`McpToolRegistry` goes through the ordinary services (`PartService`, `StockEntryService`,
`SpecDefinitionService`, …), so scoping, the parametric spec search and the stock aggregate are the
same code the web UI runs and an answer here cannot drift from what the screen shows. Two rules are
about the model's context rather than a screen:

- **Results are capped and say so.** A query matching 900 parts returns the first page, the true
  total, and a note. Silently truncating teaches the model a wrong fact about the inventory.
- **A spec value travels twice** — raw for comparing, rendered for reading. A number is stored in
  its family's base SI unit, so `capacitance` reads `1E-7`: correct, comparable and unreadable.
  `SpecValueRenderer` produces the `display` string beside it ("100n", "5 V (4.5 V ~ 5.5 V)",
  "≤ 16 V") through the same `MetricUnitFormatter` the part screen uses, so the two cannot disagree.
- `initialize` returns **instructions** telling the model what the catalogue is, that
  `list_spec_fields` comes before a parametric search (a guessed field name matches nothing rather
  than being ignored), and how to read a stored value. It pays for itself in tool calls not made.
- `get_part` sums the stock entries for `totalQuantity` rather than trusting the DTO's, which only
  the list paths fill in — a null there reads as "none in stock".
