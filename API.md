# Clele API Endpoints (all under `/api`)

The REST surface of the backend. Design notes, invariants and the reasoning behind each area live
in the feature documents under `docs/` — the section names referenced below ("see Component Cache",
"see Invitations", …) are headings in one of those. `CLAUDE.md` indexes them.

- `POST /auth/login`, `POST /auth/logout`, `GET /auth/me` — session auth (`/auth/login`,
  `/settings` and `/invitations/token/**` are the only unauthenticated `/api` endpoints); `/auth/me` includes `hasOctopartCredentials`
- `GET /settings` — app-wide settings (currency); **public** (see App Settings)
- `GET/PUT /profile/octopart` — self-service: current user's OctoPart (Nexar) credentials
  (authenticated; secret never returned)
- `GET/PUT /profile/printing` — current user's print method + preferred daemon (authenticated)
- `GET /profile/print-daemons`, `POST /profile/print-daemons/{id}/claim`,
  `PUT/DELETE /profile/print-daemons/{id}` — daemon pairing/config; the list is filtered to the
  caller's own daemons seen at the browser's current IP (authenticated). The `PUT` body carries the
  whole printer configuration — `printerType` (`BROTHER_QL`/`DYMO_CUPS`), plus `printerIp` for a
  network Brother or `printerQueue` + `mediaKeyword` for a CUPS-attached Dymo — and an omitted
  field is cleared, so the UI always submits the full form
- `POST /print-jobs`, `GET /print-jobs/{id}` — enqueue a label print job / poll its status
  (authenticated)
- **Daemon-facing** (`/api/daemon/**`, API-key auth via `X-Daemon-Id`/`X-Daemon-Key`, *not* the
  session cookie): `POST /daemon/register` (public); `GET /daemon/jobs/next?wait=` (long-poll —
  reports upward via `X-Printer-Media-*`, `X-Printer-Printable-Width`/`-Length` and
  `X-Printer-Model`, and always returns the printer configuration downward as `X-Printer-Type` plus
  `X-Printer-Ip` or `X-Printer-Queue` + `X-Printer-Media`, plus `X-Capabilities-Wanted` when the
  backend holds no capabilities; note `X-Printer-Media` travels down while `X-Printer-Media-*`
  travel up); `POST /daemon/capabilities` (the machine's CUPS queues, each with its label-size
  list — too large for a header, and it changes rarely);
  `POST /daemon/jobs/{id}/complete`
- `GET /downloads/clele-print-daemon.tar.gz` — the built daemon (static resource, not `/api`)
- `GET /organisations/selectable` — organisations the caller may switch into (authenticated);
  `GET/POST /organisations`, `GET/PUT/DELETE /organisations/{id}` — organisation management
  (requires `GLOBAL_ADMIN`); `PUT /profile/organisation` `{organisationId}` — switch the
  organisation in force for this session (authenticated). See Organisations
- `GET /users`, `GET /users/{id}` — the **members of the current organisation** (requires
  `ORG_ADMIN`); `PUT /users/{id}/permissions` — set a member's permissions in the current
  organisation only (`ORG_ADMIN`); `DELETE /users/members/{id}` — remove a user from the current
  organisation (`ORG_ADMIN`). Adding a member is **not** here — it happens by invitation, and the
  account itself is managed under `/admin/users`
- `GET /invitations`, `GET /invitations/lookup?email=`, `POST /invitations`,
  `DELETE /invitations/{id}` — invite an address to the current organisation, all `ORG_ADMIN`;
  `GET /invitations/token/{token}`, `POST /invitations/token/{token}/accept`,
  `POST /invitations/token/{token}/decline` — the invitee's side, **unauthenticated** (the token is
  the credential). See Invitations
- `GET /admin/users`, `GET /admin/users/{id}` — **every** account with **all** of its memberships;
  `POST /admin/users` (create, `organisationIds` required) / `DELETE /admin/users/{id}` (delete the
  account outright) — the only place accounts are created and deleted;
  `PUT /admin/users/{id}` — account details + global permissions;
  `POST /admin/users/{id}/organisations` `{organisationId}` /
  `DELETE /admin/users/{id}/organisations/{organisationId}` — membership;
  `PUT /admin/users/{id}/organisations/{organisationId}/permissions` `{permissions}` — permissions
  in one named organisation. All `GLOBAL_ADMIN` (see All Users)
- `GET/POST /parts`, `GET/PUT/DELETE /parts/{id}` (mutations require `PARTS_EDIT`). `POST` takes
  `PartCreateRequest` — the part's fields plus an optional opening `quantity`/`locationId`/
  `unitPrice`, created in one transaction (see *Creating a part with its stock*); `PUT` takes the
  plain `PartRequest` and cannot carry stock
  - `GET /parts?search=&categoryId=&sort=&personalNumber=&manufacturer=&locationId=&sparseSpecs=&tags=` — search
    runs in the DB: `search` matches name / part_number (case-insensitive substring) + description
    **plus `details` and the textual spec values** (PostgreSQL full-text,
    `websearch_to_tsquery`, over the single concatenated vector indexed by V43 — so "sot-23" or
    "0805" finds a part by its package, and a multi-term query may draw one term from the
    description and another from the specs); `categoryId` matches the category **and all
    descendants** (recursive CTE over `parent_id`); `sort` is `partNumber` (default) or
    `manufacturer`. The Parts page only fetches results once a search/filter is applied (it does not
    list the whole catalogue on load).
    The last five are the **advanced filters** (the collapsible "More search options" panel under
    the search bar), all optional and ANDed with the rest: `personalNumber` (exact boolean match on
    the flag), `manufacturer` (case-insensitive substring), `locationId` (parts holding stock >0 in
    that location **or any location below it** — the same recursive walk as categories),
    `sparseSpecs` (parts carrying fewer than `PartRepository.SPARSE_SPEC_THRESHOLD` spec keys — see
    *Spec coverage*), `spec` (repeated parametric criteria `jsonName:op:value` — see
    *Parametric spec search*), and `tags`
    (repeated param; a part must carry **all** of them). Tags are matched in `PartService` rather
    than SQL — they are already loaded for the DTO mapping, and "all of N" is awkward in a native
    query with a variable-length list. The SPA sends them with axios `paramsSerializer: {indexes:
    null}` so they arrive as `tags=a&tags=b` (the default `tags[]=` would not bind to a `List`).
- `GET /parts/local-match?q=` — fuzzy-match existing parts by part number (pg_trgm), used by Quick
  Add to find an already-catalogued part before searching the Internet (authenticated)
- `GET /component-cache/status` — whether the local snapshot is installed, and its date
  (authenticated, so a read-only member's Quick Add does not break on a 403);
  `GET /component-cache/search?q=` — matching cached parts, best first (empty list, never an error,
  when the cache is absent or the term is under 3 characters); `GET /component-cache/{lcsc}` — the
  whole cached record mapped onto this app's fields and spec keys. Both `PARTS_EDIT`; neither
  writes. See Component Cache
- `DELETE /parts/by-user/{userId}` — delete every part created by a user, with its stock entries,
  images and movements; returns `{deleted: n}` (requires `ORG_ADMIN`)
- `POST /parts/quick-add` — atomic create part + stock entry (requires `PARTS_EDIT`)
- `GET /parts/{id}/stock` — on-hand stock entries per location for a part
- `GET /parts/{id}/movements` — stock movement history for a part (most recent first)
- `POST /parts/auto-categorize`, `GET /parts/auto-categorize/status` — local-AI (Ollama) bulk categorization job
- `GET /parts/octopart/usage` — current user's OctoPart monthly request usage (authenticated)
- `GET /parts/octopart/search?q=` — OctoPart (Nexar) MPN search, spends one request (requires `PARTS_EDIT`)
- `POST /parts/octopart/{id}/apply` — apply a chosen OctoPart result to a part, free (requires `PARTS_EDIT`)
- `GET/POST/DELETE /parts/{id}/attachments` (`?type=` filter on GET; `type` form field on POST,
  default `PHOTO`), `POST /parts/{id}/attachments/from-url` (`{url, type}`) — photos/datasheets/files
  (mutations require `PARTS_EDIT`; GET serves bytes with the stored content-type, downloads with
  filename for datasheets/attachments)
- `GET/POST /categories`, `GET/PUT/DELETE /categories/{id}`, `GET /categories/tree`
- `GET/POST /locations`, `GET/PUT/DELETE /locations/{id}`, `GET /locations/tree` (nested hierarchy),
  `GET /locations/stats` (per-location stock roll-up — direct + subtree parts/quantity/value, drives
  the figures on the Locations tree), `GET /locations/mine` (current user's own, for stock pickers);
  `POST /locations/{id}/merge`
  (`{targetId}`) moves the location's stock into another location and deletes the source
- `GET/POST /stock-entries`, `GET/PUT/DELETE /stock-entries/{id}`; `POST /stock/{add,take,move}` are
  the Part Detail stock verbs (add / take / move-between-locations, move's destination may be any
  user's location); `POST /stock/reconcile` realigns every stock entry to its ledger sum (requires
  `PARTS_EDIT`)
- `GET /stock-thresholds?partId=` — all thresholds (optionally filtered by part) with subtree totals;
  `GET /stock-thresholds/low` — thresholds where subtree total < minimum (drives Low Stock page);
  `POST /stock-thresholds` → upsert (create or update) a threshold, 201; location must be a root
  (400 otherwise); `DELETE /stock-thresholds/{id}` → 204
- **Part kit templates** (`/part-kit-templates`, all `PARTS_EDIT` — see Part Kit Templates):
  `GET` / `GET /{id}` / `POST` / `PUT /{id}` (the whole template including its value list) /
  `DELETE /{id}` (the parts it generated are untouched); `POST /{id}/generate`
  `{quantityPerValue, locationId, unitPrice?}` creates or finds one part per value and adds stock
  to each, returning per value which it was; `GET /{id}/images` / `POST /{id}/images` (multipart) /
  `GET /{id}/images/{attachmentId}` / `DELETE /{id}/images/{attachmentId}` are the photos every
  generated part is given; `GET /{id}/generations` lists the past runs, each saying whether it can
  still be undone and — when it cannot — why, and `POST /{id}/generations/{generationId}/undo`
  takes one back (the newest only, 409 with the reason otherwise — see Undoing a generation)
- **Project BOM import** (`/projects/{projectId}/bom`, all `PARTS_EDIT` — see BOM Import):
  `GET` returns the imported BOM with every line and its match, or **204** when none has been
  imported (the normal starting state, not an error); `POST /import` (multipart `file` + optional
  `mapping` JSON + `commit`, **dry run by default**) merges a BOM export into the existing BOM and
  returns `BomImportPreviewDTO` — the detected column mapping, the file's headers, warnings, and
  the added/updated/unchanged/removed/changed/autoMatched counts with a per-line diff;
  `GET /file` downloads the file as uploaded; `GET /lines/{lineId}/candidates` returns ranked part
  suggestions with a pg_trgm similarity; `PUT /lines/{lineId}` `{partId?, status?, notes?}` records
  one line's decision (MATCHED / PROVIDED / EXCLUDED / UNMATCHED) and clears its `changed` flag;
  `POST /apply` pushes the matched lines into `project_part`, summing quantities per part
  (PLANNING only); `DELETE` drops the imported BOM, leaving `project_part` alone
- `GET /dashboard`
- `GET /parts-search?q=` — AI part search (requires `PARTS_EDIT`)
- `POST /parts/{id}/ai-apply` — apply a chosen AI-lookup result to an existing part; specs merge onto
  the part and a null column field leaves that column alone, since both arrive filtered to what the
  user confirmed. Free — the search already happened (requires `PARTS_EDIT`). Also the apply path for
  the datasheet reader, which is why it carries `details`. See *Looking a part up after it exists*
- `POST /parts/{id}/datasheet-extract?attachmentId=` — read a datasheet already stored on the part and
  propose specs + a description from it (`DatasheetExtractionDTO`); writes nothing, ~1.6¢, no web
  search. `attachmentId` optional (defaults to the part's first datasheet). Requires `PARTS_EDIT`.
  See *Reading the specs out of the datasheet*
- `GET /parts-search/images?q=` — image suggestions (requires `PARTS_EDIT`)
- `GET /parts-search/datasheets?q=&forceAi=` — datasheet links, web search first and AI as fallback
  (requires `PARTS_EDIT`).
  Returns `DatasheetSearchResponseDTO` (results **plus** the web search's outcome) — see *Blocked vs.
  empty*
- `GET /image-proxy?url=` — external image proxy
- `GET/POST /spec-groups`, `GET/PUT/DELETE /spec-groups/{id}`, `GET /spec-groups/{id}/spec-definitions`
  — spec groups and the fields inside one (see Spec Groups & Aliases)
- `POST /spec-definitions/merge` `{targetId, sourceIds}` folds duplicate spec fields into one
  (`PARTS_EDIT`); `POST /spec-definitions/move` `{specIds, groupId}` moves fields between groups
- `GET/POST /spec-definitions`, `PUT/DELETE /spec-definitions/{id}`, `POST /spec-definitions/rescan`;
  `POST /spec-definitions/{id}/convert-to-number` converts a TEXT spec to NUMBER, parsing part values into
  a base unit (dry-run unless `commit:true`; requires `PARTS_EDIT`)
- Swagger UI at `http://localhost:8080/swagger-ui.html`
