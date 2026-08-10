# AI Integration & Part Metadata Sources

Part of the Clele documentation — `CLAUDE.md` holds the overview and the index of these files; `API.md` lists the REST endpoints.

## AI Integration

- Provider: Anthropic Claude (model configured in `application.yml`, default `claude-haiku-4-5-20251001`)
- `AiPartSearchService` calls the Anthropic Messages API via RestTemplate (no SDK dependency)
- `DuckDuckGoImageService` searches for part images via DuckDuckGo
- The AI system prompt is built dynamically from `spec_definition` table — each spec's `json_name` (the exact key) plus its title, type, unit, and SELECT options are included so the AI returns specs using exact `part.specs` JSON keys for automatic pre-fill
- AI response parser handles: clean JSON, markdown-fenced JSON, and prose text preceding JSON (extracts from first `[` or ` ``` ` fence)
- **Local AI (Ollama) for part auto-categorization** — separate from the cloud Anthropic path,
  fully offline. Config: `ollama.base-url` (default `http://localhost:11434`) + `ollama.model`
  (default `qwen2.5:7b-instruct`) in `application.yml`; uses a dedicated `ollamaRestTemplate` bean (120s read).
  `PartCategorizationService` runs a single background job (own daemon thread, guarded by an
  `AtomicBoolean`): it derives the **leaf categories** (those that are not any other category's
  parent) with their breadcrumb paths, then for each part calls Ollama `/api/chat`
  (`format:"json"`, `temperature:0`) to pick a `categoryId`, validates it against the leaf set, and
  saves it in a per-part transaction (invalid/null choices leave the part unchanged). Endpoints:
  `POST /api/parts/auto-categorize` (start, 409 if already running) and
  `GET /api/parts/auto-categorize/status` (progress: total/processed/assigned/skipped/lastError).
  The Parts page has an "Auto-categorize (AI)" button that starts the job and polls status.

### What a lookup costs, and why prompt caching is not worth it

`AiPartSearchService.search` logs one INFO line per call (`ai-part-search …`) with tokens, cache
figures, web-search count, spec-definition count, elapsed time and an estimated cost. Rates live in
`anthropic.pricing.*` — **change them with the model**, since the line prices whatever `anthropic.model`
is set to and a stale rate is worse than no figure at all. `input_tokens` from the API is only the
*uncached* remainder, so the line's `promptTok` is `input + cacheWrite + cacheRead`.

**Do not add `cache_control` to the system prompt, and do not narrow the injected spec list by
category, expecting either to save money.** Both were proposed on the theory that the ~331 injected
spec definitions dominate the bill. Measured 2026-08-07 on `L7809CD2T` — 48,993 input tokens total,
of which the whole system prompt is **6,962** (counted with `/v1/messages/count_tokens` against a
byte-exact reconstruction of `buildSystemPrompt`):

| | tokens | cost | share |
|---|---:|---:|---:|
| web searches (3 × $10/1k) | — | 3.00¢ | 37% |
| search results replayed through the tool loop | ~42,000 | 4.20¢ | 52% |
| system prompt (all 331 definitions) | 6,962 | 0.70¢ | 8.6% |
| output | 446 | 0.22¢ | 3% |

So the spec list is **8.6%** of spend, not "almost all" of it. Caching it saves at most ~0.6¢ per
lookup and only from the *second* lookup inside the 5-minute TTL; a cache write costs 1.25×, so an
occasional user pays more than they save. Category scoping is worth even less — deleting the list
outright saves 0.70¢ — and it actively **breaks** caching: Haiku 4.5's minimum cacheable prefix is
**4096 tokens**, which the full prompt clears at 6,962 but a fifteen-field category-scoped prompt
(~500–800 tokens) would not, so it would silently stop caching with no error. The two levers
disable each other and neither pays for itself.

The cost is web search: the searches plus the results the model re-reads each turn. It also is not
stable — the same part measured 8.1¢/3 searches and, on another run, 5.2¢/2 searches, and SPECS.md
recorded 13¢/4 searches. It tracks how many times the model searches, **not** how large the spec
catalogue is, so growing the catalogue does not make lookups more expensive.

If lookup cost ever needs attacking, the lever is the web-search behaviour (fewer searches, or a
newer `web_search` tool version — which needs a model above Haiku 4.5, so measure the trade), not
the prompt.

## Part metadata sources — distributor APIs are off the table

**Do not propose or build an integration that pulls part specifications from a distributor API.**
Checked 2026-08-06: **Mouser** and **Farnell** both prohibit *storing* results returned by their
API — you may query and display, you may not retain. That rules them out for this app, whose whole
purpose is to keep the data. Assume **Digi-Key**, **TME** and the rest carry the same restriction
until someone reads their terms and finds otherwise; "query, display, don't retain" is the standard
shape, because the catalogue is the asset being protected.

The community **jlcparts** dataset (https://github.com/yaqwsx/jlcparts, MIT *code*) is not a way
round it: LCSC has no public API, so the data is scraped, and the project states no licence for the
data itself. Adopting someone else's unlicensed copy is a weaker position, not a stronger one.
Independently of contract terms, the EU **sui generis database right** protects the investment in
compiling a catalogue even where the individual facts are not copyrightable — which is exactly what
a distributor's parametric catalogue is.

Two sources do **not** have this problem, and the app already uses both:

- **Manufacturer datasheets.** A published document about one part, not a compiled database.
  `part.datasheet_url` is set on ~75% of the catalogue and `part_attachment` already stores the PDFs
  (see Part Attachments). Extracting parameters from them — ideally via the local Ollama, which
  costs nothing and works offline — is the intended growth path for spec coverage.
- **The AI lookup** (`AiPartSearchService`). Under Anthropic's terms the customer owns model
  outputs, so nothing restricts storing them. The constraint on that path is cost, not licensing —
  see CONSIDERATIONS.md item 5.

⚠️ **Open question on Nexar/OctoPart below**: `PartService.applyOctopart` writes Nexar's spec data
into `part.specs`, i.e. it *stores* it. Nobody has checked whether the Nexar terms carry the same
no-retention clause as Mouser and Farnell. If they do, that existing feature has the same problem
and would need to become display-only.

## OctoPart Enrichment

- Enrich an **existing** part from OctoPart — now the **Nexar Supply API** (OAuth2
  client-credentials → GraphQL). Because the API is metered, **credentials and quota are per-user**:
  each user supplies their own free Nexar contract (limited to ~100 requests/month).
- **Credentials** live on `app_user` (`octopart_client_id` / `octopart_client_secret`, secret never
  returned by the API). Users set them **self-service** on the **My Account** page (`/profile`,
  `GET/PUT /api/profile/octopart`, `ProfileController`/`ProfileService`) — no special permission, so
  any user manages their own. `UserDTO.hasOctopartCredentials` (in `/auth/me`) gates the UI.
- **Quota**: `octopart_usage(user_id, period 'YYYY-MM', request_count)`, cap from
  `octopart.monthly-limit` (default 100). `OctopartQuotaService.consumeOrThrow` runs in a
  `REQUIRES_NEW` tx and is called **after** the (free) token fetch but **before** the billable
  GraphQL query, so a request still counts if the search later fails, while invalid credentials
  (token failure) cost nothing. `GET /api/parts/octopart/usage` → `{limit, used, remaining,
  hasCredentials}`.
- **Flow**: `NexarApiService` caches the OAuth token per client id, then runs `supSearchMpn`
  (maps results → `OctopartResultDTO`: octopartId, mpn, manufacturer, description, datasheet,
  footprint-from-package-spec, specs). `OctopartService` orchestrates (creds check `428` → token →
  consume quota `429` → search). `GET /api/parts/octopart/search?q=` (PARTS_EDIT) spends one
  request.
- **Apply** is free (no Nexar call): `POST /api/parts/octopart/{id}/apply` (PARTS_EDIT) →
  `PartService.applyOctopart` sets `octopartId`, **overlays all specs**, and sets each supplied
  column field. This is a **dedicated path** because `octopartId`/`mpn`/`footprint` are not writable
  via the normal `PartRequest`/`buildPartFromRequest`.
- **Frontend** (`pages/PartDetail.tsx`): a **🔎 Search OctoPart** button shows only when the part has
  **no** `octopartId` yet and the user can edit; it displays remaining quota ("N left this month")
  and disables at zero (or links to `/profile` if no credentials). The modal does search → pick →
  **per-field checkbox confirmation** of changed real columns (specs applied wholesale; **no images
  downloaded**). `AuthContext` exposes `refresh()` so saving credentials updates the gating.
