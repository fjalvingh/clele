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

### Claude Desktop and claude.ai — the OAuth flow

Claude Desktop takes a URL and nothing else: there is nowhere in its connector dialog to put a
header, and it refuses a remote server declared in `claude_desktop_config.json` (remote ones belong
under Settings → Connectors). What it expects instead is an authorization server that registers the
client on the spot and asks the user in a browser — so **this app is its own OAuth 2.1
authorization server**, which is only reasonable because everyone it would authenticate already has
an account here.

Add it under **Settings → Connectors → Add custom connector** with the URL
`https://your-host/api/mcp`. Nothing else is typed in: the client registers itself, the browser
opens the Sortiment consent screen, and access begins when the user approves.

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
can read the catalogue and can never mint another credential.

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
