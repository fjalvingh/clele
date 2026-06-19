# Clele — Electronic Parts Stock Management

Full-stack web app for managing electronic component inventory with AI-powered part lookup.

## Build & Run

Frontend and backend ship as a **single web container**: the Maven build compiles the React/Vite
app and bundles it into the Spring Boot jar, which serves the UI and the `/api` on the same port.

- **Production build (one jar serving both)**: `mvn21 package` from `backend/` — the
  `frontend-maven-plugin` downloads a private Node into `backend/target`, runs `npm install` +
  `npm run build` (in `../frontend`), and `maven-resources-plugin` copies `frontend/dist` into the
  jar's `static/`. Run with `java -jar target/parts-0.0.1-SNAPSHOT.jar` → everything on port 8080.
- **Run the merged app from source**: `mvn21 spring-boot:run` from `backend/` builds the frontend
  too and serves it from `static/` (port 8080).
- **Fast backend-only run** (skip the npm build): `mvn21 spring-boot:run -DskipFrontend=true`
- **Frontend hot-reload dev**: `npm install && npm run dev` from `frontend/` — Vite dev server on
  port 5173 proxies `/api` to the backend on 8080. Use this alongside `-DskipFrontend=true`.
- **Frontend build check**: `npm run build` from `frontend/` (must be in that directory, not project root)
- **SPA routing**: `config/SpaWebConfig` serves static files when present and falls back to
  `index.html` for non-`/api` paths so BrowserRouter deep links / refreshes work.

## Tech Stack

- **Backend**: Spring Boot 3.2.3, Java 21, PostgreSQL, Flyway, Spring Data JPA, Spring Security, Springdoc OpenAPI, Lombok
- **Frontend**: React 19 + TypeScript, Vite 7, React Router 7, Tailwind CSS 4, Axios 1.x

## Project Structure

```
backend/src/main/java/com/clele/parts/
  config/         RestTemplateConfig (5s connect/10s read timeouts), CorsConfig (/api/**),
                  SecurityConfig (Spring Security filter chain + password encoder)
  controller/     REST controllers — all endpoints use explicit /api/... prefix
  dto/            Request/response DTOs
  model/          JPA entities: Part, Category, Location, StockEntry, PartImage, SpecDefinition,
                  AppUser; Permissions (authority-string constants)
  repository/     Spring Data JPA repositories
  service/        Business logic

frontend/src/
  api/            Axios client (client.ts), API functions (index.ts), TypeScript types (types.ts)
  auth/           AuthContext (current-user provider + useAuth hook)
  components/     Reusable UI: Layout, DataTable, Modal, FormField, Badge
  pages/          Route pages: Dashboard, Parts, PartDetail, Categories, Locations,
                  LowStock, QuickAdd, SpecDefinitions, Users, Profile, Login
```

## Database

- PostgreSQL: database `partsdb`, user `partsuser`, password `partspass`
- Schema managed by Flyway migrations (V1–V10) in `backend/src/main/resources/db/migration/`
  - V5 added `part.footprint/mpn/octopart_id` columns and the `stock_movement` ledger table
  - V6 added `spec_definition.json_name` (machine key matching `part.specs` JSON keys), dropped
    the unique constraint on `name`, and wiped the old (mismatched) spec_definition records
  - V7 seeds a standard Octopart/Digi-Key-style category taxonomy (~157 rows, 2–3 levels:
    Passives, Semiconductors→ICs→Logic/Analog/Power/MCU/Memory/Interface/Clock/RF,
    Optoelectronics, Connectors, Electromechanical, Sensors, Power, Cables, Hardware, Modules).
    Fresh-replace: it deletes the old ad-hoc demo/test category rows (safe — no part referenced a
    category) and inserts the tree with explicit ids, then realigns `category_id_seq`. The manual
    `db/seed_74xx.sql` is now superseded for categories (its 74xx tree lives under ICs→Logic ICs)
  - V9 adds a GIN full-text index on `part.description` (`to_tsvector('english', …)`) backing the
    Parts description search
  - V10 adds the `app_user` table (note: `user` is reserved in PostgreSQL) + `app_user_permission`
    child table, and seeds a bootstrap admin (see Authentication below)
  - V11 adds `spec_definition.major_type` (display grouping); V12 adds location ownership
    (`location.owner_id` + `app_user.default_location_id`, locations are per-user)
  - V13 adds per-user OctoPart (Nexar) credentials (`app_user.octopart_client_id` /
    `octopart_client_secret`) + the `octopart_usage(user_id, period 'YYYY-MM', request_count)`
    monthly request-quota table (see OctoPart Enrichment below)
- `ddl-auto: validate` — every schema change requires a new Flyway migration. The next free version
  is **V14** (CLAUDE.md previously lagged the actual migrations — always check the
  `db/migration/` directory for the real high-water mark before adding one)
- Hibernate 6 + PostgreSQL: use plain `byte[]` with `columnDefinition = "bytea"` — do NOT use `@Lob` (maps to OID, which is wrong)
- Hibernate 6 + PostgreSQL: a `@Column(length = N)` String validates against `varchar(N)` — use
  `VARCHAR(n)` (not `CHAR(n)`, which maps to `bpchar` and fails `ddl-auto: validate`) in migrations

## Key Patterns & Gotchas

- **Axios client** (`api/client.ts`) sets default `Content-Type: application/json`, sends `withCredentials: true` (the session cookie), and converts all errors to `new Error(message)` via interceptor; on HTTP 401 it redirects to `/login` (except while already on login or probing `/auth/me|/auth/login`). In catch blocks use `(err as Error).message`, never `err.response.status`. For multipart uploads, explicitly override the Content-Type header.
- **TypeScript** `verbatimModuleSyntax` is enabled — use `import { type Foo }` for type-only imports
- **Global exception handler** returns errors under key `"error"` (not `"message"`)
- **Flyway**: `flyway-core` alone handles PostgreSQL in Flyway 9.x — do not add `flyway-database-postgresql` (not managed by Spring Boot 3.2 BOM)
- **Multipart upload limit**: 10MB configured in `application.yml`
- **Image proxy** (`/api/image-proxy?url=`): proxies external images through the backend with browser-like headers. Accepts any HTTP(S) host. Used by Quick Add to avoid CORS and Cloudflare bot-protection issues.

## Authentication & Authorization

- **Session-cookie auth** via Spring Security (`config/SecurityConfig`). Users are `app_user` rows
  (email + BCrypt `password_hash` + full name + phone) with a set of **permission strings**
  (`app_user_permission`). Permission strings are used **directly as Spring Security authorities**.
- **Permissions** are defined as constants in `model/Permissions.java` and mirrored in the frontend
  `api/types.ts` `PERMISSIONS` list (key → label):
  - `PARTS_EDIT` — "Add/edit parts"
  - `USERS_EDIT` — "Add/edit users"
- **Login flow**: `POST /api/auth/login` runs the `AuthenticationManager`, persists the
  `SecurityContext` to the HTTP session via `HttpSessionSecurityContextRepository`, returns the
  `UserDTO`. `POST /api/auth/logout` invalidates the session. `GET /api/auth/me` returns the current
  user (401 if anonymous). Auth is loaded by `AppUserDetailsService` (find by email → authorities).
- **Enforcement**:
  - All `/api/**` requires an authenticated session **except** `/api/auth/login` (and swagger /
    api-docs). Static SPA assets + the client-router fallback are public.
  - Specific mutations are gated with method security (`@EnableMethodSecurity` +
    `@PreAuthorize("hasAuthority('…')")`): part mutations (create/update/delete, image
    upload/from-url/delete, quick-add, auto-categorize, OctoPart search/apply) require `PARTS_EDIT`;
    all `/api/users` endpoints require `USERS_EDIT`. `/api/profile/**` (self-service settings) and
    `/api/parts/octopart/usage` are authenticated-only (no specific permission).
  - **Not yet gated** (authenticated-only, no specific permission): categories, locations, specs,
    stock-entry mutations — easy to tighten by adding `@PreAuthorize`.
- **CSRF is disabled** for the API (token-style JSON API; SameSite cookie). Unauthenticated/forbidden
  API calls return JSON `{"error": …}` with status 401/403 (custom entry point / access-denied
  handler) so the SPA can react. `CorsConfig` sets `allowCredentials(true)` so the dev Vite proxy
  origin can send the cookie.
- **Frontend**: `auth/AuthContext` (`AuthProvider` + `useAuth`) loads `/auth/me` on mount and exposes
  `user`, `hasPermission(key)`, `login`, `logout`. `App.tsx` wraps routes in `AuthProvider`, exposes a
  public `/login`, and guards app routes with `RequireAuth` (redirect to `/login`, preserving `from`).
  The sidebar (`components/Layout`) hides permission-gated nav (Users) and shows the current user +
  logout. The Parts page hides New/Edit/Delete/categorize controls without `PARTS_EDIT`.
- **Bootstrap admin** (seeded by migration V10): `admin@clele.local` / `admin` with both permissions.
  **Change this password after first login** (via the Users screen). To regenerate the seed hash use a
  BCrypt hash of the new password (Spring's `BCryptPasswordEncoder`, or `htpasswd -bnBC 10 "" <pw>`).

## Partsbox Import

- Java CLI tool that loads a [Partsbox](https://partsbox.com) **WebSocket capture** into Clele.
  Package `com.clele.parts.imports`: `PartsboxImportRunner` (`ApplicationRunner`, active
  only under the `import` profile) + `PartsboxImportService` + `PartsboxTransitReader`.
- **Source = `data.txt`**, a capture of Partsbox's Sente (`/chsk`) WebSocket messages, which
  are **Transit+JSON**. The `core/initial-data` frame for table `:parts` holds the full
  *enriched* record for every part — far more than the plain JSON export (which lacks
  descriptions, real manufacturers, datasheets, specs, images). Decoded via the
  `com.cognitect:transit-java` dependency (version pinned in `pom.xml`; not in the Spring BOM).

### Capturing `data.txt` from Partsbox

Partsbox has no rich export, so the data is captured from the live web app's WebSocket:

1. Log in to [partsbox.com](https://partsbox.com) in Chrome and open the parts list.
2. Open DevTools (F12) → **Network** tab → filter **WS** → reload the page so the socket
   reconnects. Click the **`chsk`** WebSocket entry, then its **Messages** tab.
3. Partsbox pushes the catalogue on connect as `core/initial-data` frames (one per table:
   `:parts`, `:storage`, …). The `:parts` frame is the large one (~2 MB).
4. Select all received (↓) messages in the Messages pane and copy them into `data.txt` at
   the repo root. Each line is `<transit-payload>\t<bytecount>` with a timestamp line
   between frames; `PartsboxTransitReader` tolerates that framing and ignores non-`core/
   initial-data` lines, so a full copy of the message log is fine. Re-sent duplicate frames
   are deduped by `part/id`.
- Run it (Flyway runs first, then it imports and exits — `application-import.yml` sets
  `web-application-type: none`):
  ```
  cd backend
  mvn21 spring-boot:run -Dspring-boot.run.profiles=import \
    -Dspring-boot.run.arguments=--partsbox.file=../data.txt
  ```
- **Two phases**: (1) `@Transactional` load — wipe (`stock_movement`, `stock_entry`,
  `part_image`, `part`; keeps categories/specs/locations) then parts + stock; (2) image
  download outside that transaction (each `PartImageService.uploadFromUrl` is its own tx),
  tolerating individual failures. Idempotent / re-runnable.
- Mapping: `part/name` → unique `part_number` (duplicate names merged into one part);
  `:storage` rows → `location` (find-or-create by name). Enriched fields → `description`
  (part/description → octopart `main-description` fallback), `manufacturer`, `mpn`,
  `footprint`, `octopart_id`, `datasheet_url` (first `:datasheets`), and `specs` JSONB
  (octopart `:specs`, flattening `{v}` / `{minv,maxv}`). Octopart + SnapMagic image URLs are
  downloaded into `part_image` (≤5). Each `part/stock` transaction → a `stock_movement` row;
  `stock_entry` is the per-part/location on-hand aggregate (Σ movements, last positive price).
  Empty strings import as NULL.
- **Expected results** (current `data.txt`): 1064 parts, 1169 stock movements, 1051 stock
  entries, on-hand sum 15116; ~925 images downloaded, ~368 image failures. Image failures are
  normal and non-fatal — Partsbox's CDN returns `403 AccessDenied` for some objects, and
  SnapMagic returns gzip'd HTML placeholders (HTTP 200, not an image) where it has no photo.

## Stock Model

- `stock_entry` = on-hand aggregate (one row per part+location; read by dashboard,
  low-stock, part-detail). `stock_movement` = ledger of individual movements (history).
  The importer writes both; manual stock edits currently only touch `stock_entry`.

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

## Key Features

- **CRUD** for parts, categories (hierarchical), locations, stock entries
- **User accounts & login** with permission-based UI gating + backend enforcement (see
  Authentication & Authorization above); Users management screen + add/edit modal
- **Parts search screen**: searches on demand (name / part number / description full-text), filters
  by category subtree, sortable by part number or manufacturer
- **Dashboard** with low stock alerts
- **Quick Add wizard** (3-step): AI part search → select result → confirm details + stock entry
  - AI returns specs keyed by each spec definition's `jsonName` → auto-fills spec fields in the confirm step
  - Image picker fetches suggestions via DuckDuckGo, displays through backend proxy, uploads selected images as multipart blobs (client-side fetch + multipart upload to avoid Cloudflare/CORS issues)
  - Shows error feedback if image uploads fail (with link to navigate to saved part)
  - Location field defaults to last used location (persisted in `localStorage` key `quickadd.lastLocationId`)
- **Part images**: upload/delete photos per part (max 5), stored as PNG BYTEA in DB
- **Spec definitions**: configurable specification fields (text, number, boolean, select) with units; can be associated with categories
  - Each definition has a `jsonName` (the exact key stored inside `part.specs`) separate from its
    human-readable `name`/title. All matching (AI prompt, Quick Add, Parts edit, Part detail) keys off `jsonName`
  - **"Rescan from parts"** (`POST /api/spec-definitions/rescan`, button on the Spec Definitions page):
    scans every part's `specs` JSON and upserts a definition per distinct key, inferring the data type
    and possible values. Upsert by `jsonName` preserves manually-edited title/unit while refreshing the
    inferred dataType/options. Inference: all-boolean → BOOLEAN; all-numeric → NUMBER; string set ≤30
    distinct with no digit-bearing value → SELECT (values become options); else TEXT
  - New definitions get a default title from `SpecNameHumanizer` — it splits separators + camelCase,
    then word-segments lowercase-concatenated keys (e.g. `numberofbits` → "Number of Bits") against a
    curated electronics vocabulary, applying an acronym map (DC, I2C, RoHS, …). Unknown tokens fall back
    to a single capitalized word
- **Part detail page**: image gallery on left, details on right; thumbnail strip; per-location stock
  entries with unit price; total on-hand quantity + total stock value summary; a collapsible
  "Stock Movements" history (ledger sorted newest-first, showing quantity, location, price, comments,
  timestamp, and who made the move) backed by `GET /parts/{id}/movements`

## API Endpoints (all under /api)

- `POST /auth/login`, `POST /auth/logout`, `GET /auth/me` — session auth (login is the only
  unauthenticated `/api` endpoint); `/auth/me` includes `hasOctopartCredentials`
- `GET/PUT /profile/octopart` — self-service: current user's OctoPart (Nexar) credentials
  (authenticated; secret never returned)
- `GET/POST /users`, `GET/PUT/DELETE /users/{id}` — user management (requires `USERS_EDIT`)
- `GET/POST /parts`, `GET/PUT/DELETE /parts/{id}` (mutations require `PARTS_EDIT`)
  - `GET /parts?search=&categoryId=&sort=` — search runs in the DB: `search` matches name /
    part_number (case-insensitive substring) + description (PostgreSQL full-text,
    `websearch_to_tsquery`); `categoryId` matches the category **and all descendants** (recursive
    CTE over `parent_id`); `sort` is `partNumber` (default) or `manufacturer`. The Parts page only
    fetches results once a search/filter is applied (it does not list the whole catalogue on load).
- `POST /parts/quick-add` — atomic create part + stock entry (requires `PARTS_EDIT`)
- `GET /parts/{id}/stock` — on-hand stock entries per location for a part
- `GET /parts/{id}/movements` — stock movement history for a part (most recent first)
- `POST /parts/auto-categorize`, `GET /parts/auto-categorize/status` — local-AI (Ollama) bulk categorization job
- `GET /parts/octopart/usage` — current user's OctoPart monthly request usage (authenticated)
- `GET /parts/octopart/search?q=` — OctoPart (Nexar) MPN search, spends one request (requires `PARTS_EDIT`)
- `POST /parts/octopart/{id}/apply` — apply a chosen OctoPart result to a part, free (requires `PARTS_EDIT`)
- `GET/POST/DELETE /parts/{id}/images`, `POST /parts/{id}/images/from-url`
- `GET/POST /categories`, `GET/PUT/DELETE /categories/{id}`, `GET /categories/tree`
- `GET/POST /locations`, `GET/PUT/DELETE /locations/{id}`
- `GET/POST /stock-entries`, `GET/PUT/DELETE /stock-entries/{id}`
- `GET /dashboard`
- `GET /parts-search?q=` — AI part search
- `GET /parts-search/images?q=` — image suggestions
- `GET /image-proxy?url=` — external image proxy
- `GET/POST /spec-definitions`, `PUT/DELETE /spec-definitions/{id}`, `POST /spec-definitions/rescan`
- Swagger UI at `http://localhost:8080/swagger-ui.html`
