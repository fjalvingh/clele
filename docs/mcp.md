# MCP — letting an AI read the catalogue, and build from it

Part of the Clele documentation — `CLAUDE.md` holds the overview and the index of these files;
`API.md` lists the REST endpoints.

**`POST /api/mcp` is a Model Context Protocol server over the catalogue**, so an assistant
(Claude Code, Claude Desktop, anything else speaking MCP) can answer "do I have a 100 nF 0805 in
stock?" against the real inventory instead of guessing. It is authenticated by an API key or an
OAuth token, and scoped to exactly one organisation.

## What a client can do

Eight tools ask questions:

| tool | answers |
|---|---|
| `search_parts` | free-text and **parametric** search (`spec: ["supplyvoltage:gte:3.3"]`), plus category / manufacturer / tag / in-stock filters |
| `get_part` | one part in full: every spec (raw *and* rendered), stock per location, tags, datasheet |
| `list_spec_fields` | the spec fields and their `jsonName` — what `search_parts`' `spec` filter expects |
| `list_categories` | the category tree, flattened, with part counts |
| `list_locations` | the storage locations, flattened |
| `list_low_stock` | parts under their minimum — what needs reordering |
| `search_projects` | the caller's own projects, filtered by name substring and status |
| `get_project` | one project with every line of its parts list: need, holding, shortfall |

and two change something:

| tool | does |
|---|---|
| `add_project_part` | puts a part on a project's parts list — **and takes it off the shelf** |
| `remove_project_part` | takes the line off again, **returning everything the project held** |

## The catalogue is read-only; a project's parts list is not

Nothing here creates or edits a part, a category, a location or a spec. That has not changed, and it
is still most of the point: an assistant given a key cannot be talked into rewriting the inventory.

The parts list of a *project* is the exception, and it is not a quiet one. A project is ACTIVE or
CANCELLED, and while it is active every line of its list is out of stock and held by the project
(`docs/projects.md`) — there is no state in which a project lists a part but has not taken it. So
`add_project_part` **moves stock** and so does `remove_project_part`. There is no way to offer this
feature at all without that, which is why it is said twice in the tool descriptions and again in the
`initialize` instructions: a model that thinks it is editing a shopping list is emptying drawers.

- **Both write tools are gated on `PARTS_EDIT`** in the organisation the credential was issued for —
  the same permission `ProjectController` demands of a browser. The check lives in
  `McpToolRegistry.requireWriteAccess`, not on the service, because a tool calls `ProjectService`
  directly and so never passes that controller's `@PreAuthorize`. A key whose owner may only read
  gets a failed tool result naming the permission, and the service is never reached.
- **Projects are private to their owner**, so these tools see only the projects belonging to the
  user the credential acts as — `ProjectService.requireOwnProject` scopes by owner as well as
  organisation, and someone else's project is a 404 rather than a 403.
- **A cancelled project refuses every write** (`requireActiveProject`), which arrives as a failed
  tool result telling the model to reactivate it first.
- **Running short is a state, not an error.** Adding a part the shelf cannot cover still adds the
  line, holding what there was; the result reports `takenFromStock` and a note naming the shortfall.
  Refusing would be worse — the whole point of the parts list is to *show* what is missing.
- `remove_project_part` takes the line away entirely, need and all. Handing parts back while keeping
  the line (the UI's *Return*) is a different operation and is deliberately not exposed: a model
  cannot silently un-need something.

Both write tools resolve the project by id or exact name and the part by id or exact part number,
and a name that matches nothing comes back with the near misses rather than a bare failure. Nothing
is guessed: an ambiguous name is refused and asks for the id, because the wrong project looks
exactly like the right one in a tool result — and here that costs a drawer.

## Connecting a client

Issue a key in **My Account → AI access (MCP)**; the token is shown once. Then:

```
claude mcp add --transport http sortiment https://your-host/api/mcp --header "X-Api-Key: clele_mcp_…"
```

`Authorization: Bearer <token>` works too, for clients that only send that. The Profile screen
prints the whole command with the token already in it.

### Claude Desktop and claude.ai — the OAuth flow

Claude Desktop takes a URL and nothing else: there is nowhere in its connector dialog to put a
header, and it refuses a remote server declared in `claude_desktop_config.json` (remote ones belong
under Settings → Connectors). What it expects instead is an authorization server that registers the
client on the spot and asks the user in a browser — so **this app is its own OAuth 2.1
authorization server**, which is only reasonable because everyone it would authenticate already has
an account here.

Add it under **Settings → Connectors → Add custom connector** with the URL
`https://your-host/api/mcp`. Nothing else is typed in: the client registers itself, the browser
opens the Sortiment consent screen, and access begins when the user approves. The token that comes
out carries the approving user's own permissions, so it can change a project's parts list exactly
when that user could.

⚠️ **HTTPS.** An OAuth redirect must be HTTPS or loopback, so a connector against a plain `http://`
host on the network will not complete. A local instance on `http://localhost:8080` is fine.

What happens, in order:

1. The client POSTs to `/api/mcp` with no token and gets **401** carrying
   `WWW-Authenticate: Bearer resource_metadata="…/.well-known/oauth-protected-resource"`. That
   header is the entire discovery mechanism — without it a client that could have logged the user
   in just reports a failure.
2. It reads that document (RFC 9728) for the `resource` and its `authorization_servers`, then the
   authorization server's own metadata at `/.well-known/oauth-authorization-server` (RFC 8414).
3. It registers itself at `/api/oauth/register` (RFC 7591) and gets a `client_id`.
4. It opens a browser at `/api/oauth/authorize` with a PKCE challenge and the `resource` it wants a
   token for. The browser lands on the SPA's **consent screen** (`/oauth/consent`), logging in
   first if needed.
5. The user picks an organisation and approves; the browser goes back to the client with a code.
6. The client exchanges the code at `/api/oauth/token` for an access token and a refresh token, and
   uses the access token as `Authorization: Bearer` on every MCP call from then on.

### Where the security actually is

**Registration is open and grants nothing.** Anyone may register a client; what they get is an
identifier and the right to *ask*. Access exists only after a logged-in user has approved that
client in the browser, and never exceeds what that user can see. This is why an unauthenticated
registration endpoint is not the hole it first looks like — and why the consent screen shows the
client's self-declared name as a *claim* ("an application calling itself…") beside the redirect
host, which is the part an attacker cannot forge.

- **An error is only redirected to a URI already proved to belong to the client.** Everything
  checked before that point — the client id, the redirect URI itself — fails to a page the user
  sees. Getting this backwards turns `/authorize` into an open redirector for any address an
  attacker names.
- **Redirect URIs are matched exactly**, never by prefix, and may only be HTTPS, loopback, or a
  private application scheme the operating system routes locally.
- **PKCE is required and must be S256.** OAuth 2.1 drops `plain`, which protects nothing.
- **A code is single-use, and a replay is treated as a theft**: the tokens that code already
  produced are revoked, not just the second attempt refused. Likewise a refresh token is rotated on
  every use, and a rotated-away one coming back revokes the whole family.
  ⚠️ **Both revocations run in their own transaction** (`OAuthRevocationService`, `REQUIRES_NEW`).
  They are followed by a thrown rejection, and a throw rolls back the transaction it happened in —
  which silently undid the revocation and left the stolen token working. It passed every test that
  only checked the rejection; only an end-to-end run that used the token *afterwards* caught it.
- **Tokens are audience-bound.** The `resource` a client asks for is recorded on the token and
  checked on every call, so a token issued for somewhere else is refused here however valid it is
  there.
- **Membership is re-checked on every call**, exactly as for an API key: a stored credential must
  not outlive the access it was granted under.
- Tokens are opaque and stored as SHA-256 — not BCrypt, which cannot be looked up, and not a JWT,
  which would mean managing a key to tell ourselves something a primary-key lookup already answers
  and could not be revoked.

Access tokens last an hour, refresh tokens thirty days, an authorization request ten minutes and an
issued code five.

### An API key is still the right thing for a headless client

Claude Code, `curl` and scripts have no browser to complete a consent in, so the `X-Api-Key` route
of V57 stays. The two differ in how they are obtained and in nothing else: both resolve to an
`McpPrincipal` — a user, an organisation, and that user's authorities there — and
`McpApiKeyAuthFilter` picks between them by the credential's shape (an API key announces itself
with `clele_mcp_`).

**`/api/mcp` is scoped to its own security chain, and key management is not on it.** A key or token
can read the catalogue, can change a project's parts list if its owner holds `PARTS_EDIT`, and can
never mint another credential.

### `mcp-remote`, if you would rather not use the browser flow

A stdio bridge that forwards to the HTTP endpoint with a header attached still works, and is the
quickest way to point a config-file client at an API key:

```json
{
  "mcpServers": {
    "sortiment": {
      "command": "npx",
      "args": [
        "-y", "mcp-remote",
        "https://your-host/api/mcp",
        "--header", "X-Api-Key:${SORTIMENT_KEY}"
      ],
      "env": { "SORTIMENT_KEY": "clele_mcp_…" }
    }
  }
}
```

⚠️ **No space after the colon, and the value in `env`.** Claude Desktop on Windows (and Cursor, and
Codex CLI) does not escape spaces inside `args` when it invokes `npx`, which mangles
`"X-Api-Key: clele_mcp_…"` into something the server never sees as a key. `--header-file <path>`
avoids the question entirely. Verified with mcp-remote 0.8.2: no `--transport` flag is needed, and
a plain `http://` address additionally needs `--allow-http`.

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
`SpecDefinitionService`, `ProjectService`, …), so scoping, the parametric spec search, the stock
aggregate and the allocation arithmetic are the same code the web UI runs — an answer here cannot
drift from what the screen shows, and a project changed from a chat window is changed the same way
the button changes it. Two rules are
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
