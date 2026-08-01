# Clele — Electronic Parts Stock Management

Full-stack web app for managing electronic component inventory with AI-powered part lookup.

## Changelog

User-visible change notes live in `backend/src/main/resources/changes/` as HTML fragments named
`YYYYMMDD.html` (one file per date). After completing any larger change — new feature, redesigned
behaviour, removed UI element — ask the user whether a changelog entry should be written there.

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
- **Print daemon (Go)** is built by the same Maven build: `exec-maven-plugin` runs `go build`
  (linux/amd64) over `../daemon`, `maven-antrun-plugin` bundles binary + installer + systemd unit
  + README into `clele-print-daemon.tar.gz`, and `maven-resources-plugin` copies it into the jar's
  `static/downloads/` so the running app serves it at `/downloads/clele-print-daemon.tar.gz`.
  Needs a `go` toolchain on PATH; skip with `-DskipDaemon=true` (see Label Printing below).

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
  model/          JPA entities: Part, Category, Location, StockEntry, PartAttachment, SpecDefinition,
                  AppUser; Permissions (authority-string constants)
  repository/     Spring Data JPA repositories
  service/        Business logic

frontend/src/
  api/            Axios client (client.ts), API functions (index.ts), TypeScript types (types.ts)
  auth/           AuthContext (current-user provider + useAuth hook)
  components/     Reusable UI: Layout, DataTable, Modal, FormField, Badge
  pages/          Route pages: Dashboard, Parts, PartDetail, Categories, Locations,
                  LowStock, QuickAdd, SpecDefinitions, Users, Profile, Login
  utils/          labelPrint.ts (label rendering + daemon print), units.ts (metric prefixes)

daemon/           Go print daemon — single static binary, stdlib only, no external deps
  cmd/clele-print-daemon/   main.go: register / run / status / version subcommands
  internal/apiclient/       talks to /api/daemon/** (long-poll, media + version reporting)
  internal/ipp/             minimal IPP client — printer status + loaded-media detection
  internal/qlraster/        Brother QL raster protocol + measured print geometry
  install.sh, clele-print-daemon.service, README.md
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
  - V14 adds part ownership (`part.created_by_id`, NOT NULL → `app_user`): every part records the
    user who created it (existing parts backfilled to the bootstrap admin). Lets an admin delete one
    user's parts without affecting the rest of the catalogue (see Part Ownership below)
  - V15 enables the `pg_trgm` extension (trusted; `partsuser` can install it) and adds a GIN
    trigram index on `part.part_number`, backing Quick Add's fuzzy "do we already have this part?"
    lookup (see Quick Add below)
  - V16 adds the Spring Session JDBC tables (`spring_session` + `spring_session_attributes`,
    canonical PostgreSQL schema) so HTTP sessions persist in the DB and logins survive an app
    restart (see Authentication below). These tables are not JPA-mapped, so their `CHAR(36)`
    columns are exempt from `ddl-auto: validate`
  - V17 adds `stock_movement.type` (`MovementType`: PURCHASE/CONSUME/ADJUST/INITIAL/MOVE/IMPORT) and
    backfills the ledger so the invariant `stock_entry.quantity == Σ stock_movement.quantity` holds
    for every (part, location): existing importer movements are tagged `IMPORT`, and each drifted
    aggregate (manual edits made before the funnel existed) gets one reconciling movement (see Stock
    Model below)
  - V18 drops `stock_movement.currency` — the app no longer stores a per-movement currency; it uses a
    single app-wide currency from config (see App Settings below)
  - V19 generalizes `part_image` into `part_attachment`: renames the table/sequence/index, renames
    `image_data` → `data`, and adds `type` (`AttachmentType`: PHOTO/DATASHEET/ATTACHMENT),
    `content_type`, and `filename` (NULL for photos). Existing rows backfill to `PHOTO`/`image/png`.
    One bytea table now holds photos, datasheets, and user attachments (see Part Attachments below)
  - V20 adds `spec_definition.metric_prefix` (BOOLEAN, default false): a NUMBER spec whose value is
    stored in a base SI unit (the `unit` column) is rendered/edited with metric prefixes (0.009 A →
    "9 mA") — see Spec definitions below
  - V21 adds `location.parent_id` (self-FK, nullable, indexed): locations are now hierarchical
    (Building A > Room B > Cupboard C). NULL parent = root. Parts can be stored at any level (stock
    entries reference a location id regardless of depth) — see Locations below
  - V22 renames `app_user.default_location_id` → `last_location_id` (FK recreated with `ON DELETE
    SET NULL`) — the per-user location pointer is now the *last-used* location, updated automatically
    on every stock add, not a managed account setting. Also drops the stale `location_owner_name_key`
    unique constraint (per-owner global name uniqueness) left over from before V21: names may now
    repeat under different parents; sibling uniqueness is enforced in the service — see Locations below
  - V23 adds a changelog mechanism (`changes/` HTML fragments + `ChangelogController`)
  - V24 introduces `part_stock_threshold` (see Stock Thresholds below) and drops
    `stock_entry.minimum_quantity` — thresholds are now per (part, root location), not per stock
    entry. Existing `minimum_quantity > 0` rows are migrated to the new table (MAX per root).
  - V31–V34 add label printing (see Label Printing below): V31 `print_daemon` + `print_job` +
    `app_user.print_method`/`preferred_daemon_id`; V32 a manual tape width (V34 drops it again);
    V33 `print_daemon.version`; V34 replaces the manual width with the media the daemon detects
    from the printer (`media_kind`/`media_width_mm`/`media_length_mm`/`media_name`); V35 adds
    `app_user.print_barcode_label` (also print a barcode label — see Barcode labels below)
  - V36 introduces **organisations**, the tenant boundary (see Organisations below): the
    `organisation` table + `app_user_organisation` membership + `app_user.last_organisation_id`, an
    `organisation_id NOT NULL` on `part`/`category`/`location`/`spec_definition`/`tag`/`project`,
    per-organisation uniqueness (`part_number`, `spec_definition.json_name`, `LOWER(tag.name)`),
    and **drops `location.owner_id`** (locations are org-owned now). It seeds "Initial Organisation"
    (all existing data) + "Template" (a copy of the taxonomy/specs/tags), and grants the new
    `GLOBAL_ADMIN` permission to every `USERS_EDIT` holder
  - V37 makes permissions **per-organisation**: adds `app_user_organisation_permission(user_id,
    organisation_id, permission)` and the `ORG_ADMIN` permission, leaving `app_user_permission` for
    global permissions only (`GLOBAL_ADMIN`). Existing permissions are copied into every
    organisation the holder belongs to, and every `USERS_EDIT` holder also gains `ORG_ADMIN` — so
    nobody gains or loses anything they could already do
- `ddl-auto: validate` — every schema change requires a new Flyway migration. The next free version
  is **V38** (always check `db/migration/` for the real high-water mark before adding one)
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
- **Icons**: always use inline SVG icons in the UI, never Unicode/emoji glyphs (📄, 📎, ⬇, …) — they
  render as empty boxes when the platform font lacks the glyph. Use a `currentColor` stroke SVG so it
  inherits the surrounding text color.

## Authentication & Authorization

- **Session-cookie auth** via Spring Security (`config/SecurityConfig`). Users are `app_user` rows
  (email + BCrypt `password_hash` + full name + phone) with a set of **permission strings**
  (`app_user_permission`). Permission strings are used **directly as Spring Security authorities**.
- **Permissions are per-organisation** (V37), except `GLOBAL_ADMIN`. Defined as constants in
  `model/Permissions.java` (`GLOBAL` / `PER_ORGANISATION` sets) and mirrored in the frontend
  `api/types.ts` as `GLOBAL_PERMISSIONS` / `ORGANISATION_PERMISSIONS`:
  - `ORG_ADMIN` — "Organisation Admin": organisation-level administration (the Admin Actions
    screen), managing who belongs to the organisation and their permissions **within it**
  - `USERS_EDIT` — "Invite users" into the organisation (the invitation flow is not built yet;
    membership is currently managed by an `ORG_ADMIN`)
  - `PARTS_EDIT` — "Add/edit parts"
  - `GLOBAL_ADMIN` — **global**: add/edit organisations and user accounts, switch into any
    organisation (including the template), and implicitly hold **every** per-organisation
    permission everywhere. That implication is what makes a newly created, memberless organisation
    usable at all (`AppUser.permissionsIn`)
- **Authorities follow the current organisation.** `AppUserDetailsService` grants only the *global*
  permissions at authentication time — the per-organisation set is unknown until an organisation is
  in force, and changes when the user switches. `service/PermissionService.applyAuthorities`
  re-issues the `Authentication` with `global + permissionsIn(currentOrg)` and re-saves it through
  the `SecurityContextRepository` (required in Spring Security 6 — mutating the held context is not
  persisted). It is called at login (`AuthController`) and on every switch (`ProfileController`).
  **This is what keeps every existing `@PreAuthorize("hasAuthority('…')")` working unchanged.**
  Consequence: editing a user's permissions does not affect their *current* session — the change
  takes effect on their next switch or login.
- **Login flow**: `POST /api/auth/login` runs the `AuthenticationManager`, persists the
  `SecurityContext` to the HTTP session via `HttpSessionSecurityContextRepository`, returns the
  `UserDTO`. `POST /api/auth/logout` invalidates the session. `GET /api/auth/me` returns the current
  user (401 if anonymous). Auth is loaded by `AppUserDetailsService` (find by email → authorities).
- **Session persistence**: sessions are stored in PostgreSQL via `spring-session-jdbc`
  (`spring.session.store-type: jdbc`, schema owned by Flyway V16 with
  `spring.session.jdbc.initialize-schema: never`), so logins survive an app restart. The timeout is a
  **7-day sliding idle window** (`server.servlet.session.timeout: 7d`) — each request resets it;
  Spring Session reaps expired rows hourly.
- **Enforcement**:
  - All `/api/**` requires an authenticated session **except** `/api/auth/login`, `/api/settings`
    (and swagger / api-docs). Static SPA assets + the client-router fallback are public.
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
  `part_attachment`, `part`; keeps categories/specs/locations) then parts + stock; (2) image
  download outside that transaction (each `PartAttachmentService.uploadFromUrl(..., PHOTO)` is its
  own tx), tolerating individual failures. Idempotent / re-runnable.
- Mapping: `part/name` → unique `part_number` (duplicate names merged into one part);
  `:storage` rows → `location` (find-or-create by name). Enriched fields → `description`
  (part/description → octopart `main-description` fallback), `manufacturer`, `mpn`,
  `footprint`, `octopart_id`, `datasheet_url` (first `:datasheets`), and `specs` JSONB
  (octopart `:specs`, flattening `{v}` / `{minv,maxv}`). Octopart + SnapMagic image URLs are
  downloaded into `part_attachment` as PHOTO rows (≤5). Each `part/stock` transaction → a `stock_movement` row;
  `stock_entry` is the per-part/location on-hand aggregate (Σ movements, last positive price).
  Empty strings import as NULL.
- **Expected results** (current `data.txt`): 1064 parts, 1169 stock movements, 1051 stock
  entries, on-hand sum 15116; ~925 images downloaded, ~368 image failures. Image failures are
  normal and non-fatal — Partsbox's CDN returns `403 AccessDenied` for some objects, and
  SnapMagic returns gzip'd HTML placeholders (HTTP 200, not an image) where it has no photo.

## Stock Model

- `stock_entry` = on-hand aggregate (one row per part+location; read by dashboard,
  low-stock, part-detail). `stock_movement` = ledger of signed-delta movements (history) and the
  **source of truth**: the invariant `stock_entry.quantity == Σ stock_movement.quantity` holds per
  (part, location).
- **Every on-hand change funnels through `StockMovementService.apply(part, location, deltaQty,
  unitPrice, comments, type)`** — it writes one `StockMovement` (delta) and updates the `stock_entry`
  aggregate in the same transaction, checks the location is in the current organisation, and rejects changes that would drive
  stock negative. All manual paths route through it: `StockEntryService.create` (delta `+qty`,
  `INITIAL`), `update` (delta `new−old`, `ADJUST`; a price-only edit writes no movement),
  `delete` (delta `−qty`, `ADJUST`, then drops the row); `QuickAddService` (delta `+qty`, `INITIAL`).
- **Part detail stock operations** are the explicit user-facing verbs (no "Edit" — adjusting an
  absolute quantity was unclear): `StockEntryService.addStock` (delta `+qty`, `PURCHASE`, find-or-
  create, also (re)sets the price), `takeStock` (delta `−qty`, `CONSUME`), and `move`
  (transfer between two locations). Each is a `POST /api/stock/{add,take,move}`. **Move** writes two
  `MOVE` movements — a negative leg at the source and a positive leg at the destination, each with a
  comment naming the other location ("Moved to …" / "Moved from …") for a clear trace — and carries
  the source entry's unit price to the moved stock. Both locations must be in the current
  organisation (`StockMovementService.requireCurrentOrganisation`); since V36 there is no per-user
  restriction within one, so every member sees add/take/move/remove on every stock line.
- The Partsbox importer keeps its own dated-movement loop (movements tagged `IMPORT`, entry = Σ) — it
  was already consistent. `POST /api/stock/reconcile` (`PARTS_EDIT`) realigns every aggregate to its
  ledger and returns `{corrected: n}` — a verification/safety-net hook (expect 0 in steady state).

## Stock Thresholds

- Minimum stock levels live in a **separate** `part_stock_threshold` table (V24) — one row per
  (part, root location). `stock_entry` no longer has a `minimum_quantity` column.
- A threshold is set **per root location only** (locations with `parent_id IS NULL`). "Low stock"
  means the SUM of all `stock_entry.quantity` rows for the part across the entire subtree of that
  root location is below the threshold. This covers stock spread across Building A > Room B > Shelf C
  with a single threshold on Building A.
- **`StockThresholdRepository`** uses native SQL with a recursive CTE to compute subtree totals —
  JPQL cannot express recursive CTEs so all threshold queries are native. Results map via the
  `StockThresholdView` Spring Data projection interface (getters matching column aliases).
- **`StockThresholdService`**: `findAll(partId?)`, `findLowStock()`, `countLowStock()`,
  `upsert(request)` (validates root-location constraint, returns 400 if not root), `delete(id)`.
- **Dashboard** `lowStockCount` is driven by `StockThresholdService.countLowStock()`.
- **Frontend**: the Part Detail page has a "Stock Thresholds" card showing each threshold row
  (root location, on-hand total across subtree, minimum, low-stock badge) with Add/Edit/Delete.
  The root-location picker filters `allLocations` to entries without a `parentId`. The Low Stock
  page (`pages/LowStock.tsx`) calls `getLowStockThresholds()` and shows root-location names and
  deficits.

## App Settings

- App-wide (non-user) settings live in config under `app.*` (`config/AppProperties`,
  `@ConfigurationProperties`) and are exposed to the SPA via **`GET /api/settings`**
  (`SettingsController`, **public** — permitted in `SecurityConfig`, non-sensitive).
- Currently just the **currency**: `app.currency.code` (default `EUR`) + `app.currency.symbol`
  (default `€`). There is a single app-wide currency — prices are not stored with a currency.
- **Frontend**: `settings/SettingsContext` (`SettingsProvider` in `App.tsx`, wraps the routes) loads
  `/settings` once on mount with a sensible default (`€`) so prices render before/independent of the
  fetch. `useSettings()` exposes `settings` + `formatMoney(amount)` ("€ 12.34"); used wherever prices
  display (Dashboard stock value, Part Detail unit prices + total value, stock movements).

## Organisations (multi-tenancy)

- **The tenant boundary.** Every `part`, `category`, `location`, `spec_definition`, `tag` and
  `project` carries an `organisation_id` (V36). `stock_entry`, `stock_movement`,
  `part_stock_threshold`, `part_attachment`, `project_part`, `project_stock`, `part_tag` and
  `category_spec` deliberately **do not** — they derive their organisation through
  `part_id`/`location_id`/`project_id`, so there is nothing that can drift out of sync.
- **`service/CurrentOrganisationService`** is the counterpart to `CurrentUserService` and the single
  source of the tenant: `current()`/`currentId()` read the `currentOrganisationId` **HTTP session
  attribute** (persisted by Spring Session JDBC), falling back to `app_user.last_organisation_id`
  and then the user's first membership; `switchTo(id)` sets both session and the remembered default;
  `selectable()` returns the user's memberships, or **every** organisation for a `GLOBAL_ADMIN`.
  It has **no fallback outside a request** — background jobs
  (`PartCategorizationService`, which captures the id on the request thread in `start()`) and the
  Partsbox importer (`resolveImportOrganisation()`) resolve an organisation explicitly instead.
- **The pattern for scoping** is uniform: inject `CurrentOrganisationService`, pass `currentId()`
  into the repository, stamp `organisation` on create, and load single entities via a
  `findByIdAndOrganisationId` that reports a cross-organisation id as **404, not 403** (another
  tenant's data does not exist as far as this one is concerned).
- **Uniqueness is per-organisation**: `part_number`, `spec_definition.json_name` and
  `LOWER(tag.name)` are composite-unique with `organisation_id`. `app_user.email` stays global.
- **The Template organisation** (`organisation.is_template`, a flag rather than a name — they get
  renamed) holds a blueprint taxonomy. `OrganisationService.create` clones its categories (parents
  first, remapping `parent`), spec definitions, tags and `category_spec` links into the new
  organisation; parts, locations, stock and projects are never cloned. Only a `GLOBAL_ADMIN` may
  select it. `delete` refuses the template and any organisation still holding parts/locations/projects.
- **API**: `GET /api/organisations/selectable` (authenticated — drives the switcher),
  `GET/POST/PUT/DELETE /api/organisations` (`GLOBAL_ADMIN`), `PUT /api/profile/organisation`
  `{organisationId}` (switch). `/auth/me` returns `currentOrganisationId`/`Name` +
  `selectableOrganisations` so the sidebar renders in one round trip (`UserService.toCurrentUserDTO`,
  which also blanks a `lastLocation` belonging to another organisation).
- **Frontend**: the switcher lives in the sidebar footer above the current user
  (`components/Layout.tsx`) and again on My Account; both **reload the page** after switching —
  every page fetches on mount, so only a full reload guarantees no stale cross-organisation data.
  `pages/Organisations.tsx` is the `GLOBAL_ADMIN` management screen; `pages/Users.tsx` assigns
  membership (at least one required).

## Locations

- Locations are **organisation-owned** (`location.organisation_id`, V36 — `owner_id` was dropped)
  **and hierarchical** (`location.parent_id`, self-FK, V21) — mirroring the Category tree pattern.
  Every member of an organisation shares its locations and may add/take/move stock in any of them;
  `StockMovementService.requireCurrentOrganisation` replaced the old own-location guard, and
  `applyNoOwnershipCheck` (the cross-user escape hatch for merge/move destinations) is gone. A part can be stored at any level (Building A,
  or Room B inside it, or Cupboard C inside that); `stock_entry`/`stock_movement` just reference a
  `location_id` regardless of depth.
- **Invariant**: a child shares its parent's organisation. `LocationService.resolveParent` resolves
  the parent through `findByIdAndOrganisationId`, so a cross-organisation parent is simply not found.
  Self-parenting and cycles are rejected (`isDescendant` walks the parent chain). `delete` refuses a
  location with sub-locations.
- **Sibling-name uniqueness**: an organisation may not have two locations with the same name under the same
  parent (`LocationRepository.existsSibling`, null-safe parent match for the root level). Names *may*
  repeat across different parents (two "Cupboard C"s in different rooms are fine — the breadcrumb
  disambiguates).
- `LocationDTO` carries `parentId`/`parentName`/`breadcrumb` ("Building A > Room B > Cupboard C", built
  by walking the parent chain). `GET /api/locations/tree` returns the nested `LocationTreeDTO` forest
  for the current organisation. The **Locations page** renders the tree (expand/collapse, per-node
  "+ Sub"/Edit/Delete gated by `canManage`, now simply `PARTS_EDIT`-or-admin) with a hierarchical
  parent `<select>` over the organisation's locations minus the edited subtree. Stock-add pickers (Quick Add, Part Detail) show `breadcrumb`
  instead of the bare name.
- **Breadcrumb everywhere a location is shown**: `Location.breadcrumb()` (entity method, walks the
  parent chain) is the single source. `StockEntryDTO`/`StockMovementDTO` carry both `locationName`
  (leaf) and `locationBreadcrumb` (full path); the Part Detail stock + movement tables, the Low Stock
  table, and the "Remove stock at …" confirm all render the breadcrumb (falling back to the leaf).
- **Merge into** (`POST /api/locations/{id}/merge` `{targetId}` → `LocationService.merge`): folds a
  location into another, then deletes the source. Both must be in the current organisation.
  **History is preserved**: each source
  `stock_entry`'s on-hand qty is folded into the target's aggregate (find-or-create, carrying price),
  and the source's whole ledger is **re-pointed** to the target
  (`StockMovementRepository.repointLocation`) so every movement keeps its original type, price, date
  and author under the target location — no new movements are written (that would double-count the
  re-pointed history). The re-point also frees the source of `stock_movement` references (the
  `location` FK has no cascade); the now-empty source `stock_entry` rows are dropped and the location
  deleted. Folding-the-aggregate + re-pointing-the-ledger keeps the invariant `Σ(target movements) ==
  target on-hand` per part. Rejects self-merge and a source with sub-locations. The Locations page
  exposes a per-node "Merge into" action (gated by `canManage`) opening a modal that picks any
  location as the target.
- **Last-used location** (replaces the old "default location", V22): `app_user.last_location_id` (FK,
  `ON DELETE SET NULL`) records the location a user most recently added stock to. It is **not** a
  managed account field — `CurrentUserService.rememberLastLocation(location)` updates it inside the
  add transaction from both add paths (`QuickAddService.quickAdd`, `StockEntryService.create`).
  `UserDTO`/`AuthUser` expose `lastLocationId`/`lastLocationName` (breadcrumb); the Quick Add and Part
  Detail location pickers pre-select it (and require a location — submit is disabled otherwise).
  Because the pointer is remembered across organisations, `UserService.toCurrentUserDTO` blanks it
  when the location belongs to a different one. Since V36 a new user is **not** seeded with a
  starting location (locations are organisation-owned, so a personal one is meaningless) —
  `UserRequest` takes `organisationIds` instead. Deleting a location nulls it from any user that
  last used it.

## Part Ownership

- Every `part` records its creator in `part.created_by_id` (NOT NULL → `app_user`, added in V14).
  Set once at creation and never changed by updates. All three creation paths set it:
  `PartService.create` and `QuickAddService.createPart` use the authenticated user
  (`CurrentUserService.current()`); the Partsbox importer attributes parts to the bootstrap admin
  (same owner it uses for imported locations). `PartDTO` exposes `createdById` / `createdByName`
  (full name, falling back to email); the Part Detail page shows "Added by".
- **Bulk cleanup**: `DELETE /api/parts/by-user/{userId}` (`USERS_EDIT`) →
  `PartService.deleteByUser` removes every part that user created plus its stock entries, images and
  movements, and returns the count. `stock_entry` has no `ON DELETE CASCADE`, so it is cleared first
  (`StockEntryRepository.deleteByPartIdIn`) before the bulk `Part` delete (`part_attachment` and
  `stock_movement` cascade at the DB). The Users page exposes a per-row **Delete parts** action.
- Note: `created_by_id` is a non-null FK with no cascade, so deleting a user who still has parts
  fails at the DB until their parts are removed. `deleteByUser` is scoped to the **current
  organisation**, so cleaning up a user in one organisation leaves their parts in others intact.

## Part Attachments

- A single `part_attachment` bytea table (entity `PartAttachment`, V19) stores all per-part binary
  content, distinguished by `type` (`AttachmentType`: `PHOTO`, `DATASHEET`, `ATTACHMENT`). Columns:
  `data` (bytea), `type`, `display_order`, `content_type`, `filename` (NULL for photos), `created_at`;
  `part_id` FK is `ON DELETE CASCADE`.
- **`PartAttachmentService`** branches by type:
  - `PHOTO` — PNG-normalized via ImageIO (`convertToPng` / `downloadAndConvertToPng`), `content_type`
    `image/png`, no filename, **capped at 5 per part** (`countByPartIdAndType(.., PHOTO)`).
  - `DATASHEET` / `ATTACHMENT` — stored **as-is**: original bytes, original `content_type` and
    `filename`, **uncapped**. `uploadFromUrl(.., DATASHEET)` downloads the raw file (response
    content-type preserved, filename derived from the URL path) — used by the Part Detail
    "Download from URL" button to pull the part's `datasheet_url` PDF into storage.
  - `delete` re-sequences `display_order` within the same part+type group.
- **`PartAttachmentController`** (`/api/parts/{partId}/attachments`): `GET` (optional `?type=`),
  `GET /{id}` serves bytes with the stored content-type (photos render inline with a 7-day cache;
  datasheets/attachments add `Content-Disposition: attachment; filename=…`), `POST` (multipart
  `file` + `type`), `POST /from-url` (`{url, type}`), `DELETE /{id}`. Mutations require `PARTS_EDIT`.
- **`part.datasheet_url` is unchanged** — it remains the canonical URL string; binary `DATASHEET`
  rows are an additional, optional copy. The Part Detail page has a **Documents** card listing
  datasheets and attachments (download links) with upload controls + the "Download from URL" action.
- Frontend API (`api/index.ts`): `getPartAttachments(partId, type?)`,
  `uploadPartAttachment(partId, file, type)`, `addAttachmentFromUrl(partId, url, type)`,
  `deletePartAttachment`, `attachmentUrl(partId, id)`. Photos still drive the Part Detail gallery /
  Quick Add image picker (now uploaded as `PHOTO`).

## Label Printing

Two delivery methods, chosen per user (`app_user.print_method`, `PrintMethod` BROWSER/DAEMON):

- **BROWSER** (default, unchanged): `components/PrintLabelModal.tsx` builds a self-contained HTML
  document and prints it via a hidden iframe. Fixed 50×18mm (`LABEL_W_MM`/`LABEL_H_MM`); the user
  picks the printer in the OS dialog. No backend involvement.
- **DAEMON**: silent printing to a network label printer via the Go daemon in `daemon/`. The
  browser renders the label to a PNG on a canvas, POSTs it as a job, and the daemon long-polls for
  it and drives the printer.

### Barcode labels

A part can additionally be labelled with a **Code 128** barcode identifying it *in this app*:
`CLE-` + the zero-padded part id (`CLE-000123`). The prefix is the whole point — it makes our label
distinguishable from a manufacturer/distributor barcode, so `pages/BarcodeScanner.tsx` `handleScan`
can `parsePartBarcode` it and go straight to the part, skipping the pg_trgm fuzzy match, the AI web
search *and* the rescan-dedupe guard. Anything without the prefix takes the old path unchanged.

- **`frontend/src/utils/code128.ts`** is the only implementation: `partBarcode` / `parsePartBarcode`,
  the subset-B encoder `code128bModules` (start 104, checksum `(104 + Σ i·value) % 103`, 13-module
  stop), and both renderers — `drawCode128` (canvas, daemon path) and `code128Svg` (inline SVG,
  browser path). One module list feeds both so they cannot drift.
- **Bars must survive a 1-bit 300 dpi raster** (the daemon thresholds, it does not dither): the
  module width is always an **integer number of device dots**, 2–4, chosen by `pickModuleWidth` from
  the available width, with a ≥10-module quiet zone each side. Too narrow for even 2 dots ⇒ the
  label falls back to the code as text and the modal says so (`barcodeFits` /
  `barcodeModuleWidthMm` in `utils/labelPrint.ts`).
- **It is a separate label**, never mixed with the text label. Browser path:
  `PrintLabelModal.labelDocument([...])` emits one page per label, so two labels come out of a
  single print dialog. Daemon path: two sequential jobs (`printLabelViaDaemon` then
  `printBarcodeLabelViaDaemon`, both via the shared `sendPngToDaemon`); the second is skipped if the
  first fails.
- Opt-in per print via a checkbox in `PrintLabelModal`, defaulted from and saved back to
  `app_user.print_barcode_label` (V35) through the existing `PUT /api/profile/printing`; the same
  checkbox is on My Account. Nothing changed in `PrintJob`, the daemon protocol or the Go daemon —
  a barcode label is just another PNG.

### Job flow (daemon path)

1. Daemon self-registers unauthenticated → `POST /api/daemon/register` → PENDING, no owner; the
   raw API key is returned **once** (BCrypt hash stored).
2. User claims it on **My Account → Label printing**. Only daemons whose `last_seen_ip` matches the
   *browser's current request IP* are listed, and a claimed daemon is restricted to its owner
   (see `PrintDaemonService.listForCurrentUserAtIp` / `findVisible`).
3. Daemon long-polls `GET /api/daemon/jobs/next?wait=25`. Backend replies 200 + job or 204, and
   **always** sets `X-Printer-Ip` so the daemon can query the printer before any job exists.
4. Browser renders the PNG (`utils/labelPrint.ts`) → `POST /api/print-jobs` → `PrintJobService`
   persists it and completes a `CompletableFuture` to wake a waiting daemon immediately.
5. Daemon prints, then `POST /api/daemon/jobs/{id}/complete` with DONE/FAILED + the printer's own
   error text, which the UI shows.

`PrintJobService.nextForDaemon` is deliberately **not** `@Transactional` — it blocks for the whole
wait window and must not hold a DB connection idle.

### Auth for daemons

`/api/daemon/**` has its **own** `SecurityFilterChain` (`@Order(1)`, `securityMatcher`) in
`SecurityConfig`, authenticated by `DaemonApiKeyAuthFilter` reading `X-Daemon-Id` + `X-Daemon-Key`
(stateless, no session). `/api/daemon/register` is `permitAll`. The session-cookie chain
(`@Order(2)`) is unchanged and still covers everything else.

### Daemon versioning

The daemon version (`YYYYMMDD.HHMMSS`) is the **commit timestamp of the last commit touching
`daemon/`**, so it changes only when the daemon actually changes — not per build. Computed once by
`maven-antrun-plugin` (`daemon-version` execution, `initialize` phase, `exportAntProperties`), then
(a) baked into the binary via `-ldflags -X main.version=…` and (b) written to `daemon-version.txt`
on the backend classpath as the *expected* version (`DaemonVersionService`). Daemons report theirs
via `X-Daemon-Version` on **every** call, so an in-place binary swap is reflected without
re-registration; My Account warns when they differ. Uncommitted `daemon/` changes do **not** move
the version — commit first. Unknown on either side ⇒ no warning, never a bogus one.

### Printer communication — IMPORTANT for adding printers

Currently supports **Brother QL-series network printers** (developed against a QL-710W). Two
separate channels, and the split matters:

- **Status + media detection: IPP, port 631** (`internal/ipp`, minimal stdlib Get-Printer-Attributes
  client). Reads `printer-state`, `printer-state-reasons`, `media-ready`.
- **Printing: raw TCP, port 9100** (`internal/qlraster`), Brother raster command stream.

**Port 9100 on the QL-710W is write-only** — verified against hardware, it never answers the
raster protocol's own status request (`ESC i S`), with or without an invalidate/initialize
preamble. A job written there therefore "succeeds" even while the printer flashes an error. That
is why status comes from IPP instead; `qlraster.Print` checks IPP *before* printing (refusing when
faulted, reporting the real reason) and again after.

**Media is detected, never configured.** A Brother QL job must declare the media in its
print-information command (`ESC i z`) — kind (continuous vs die-cut), width, and for die-cut the
fixed length. Declaring anything other than what is loaded is rejected as a media error (flashing
red LED, generic "ERROR"). The daemon reads it from IPP and reports it to the backend via
`X-Printer-Media-*` headers on every poll (throttled to ~1/min), stored on `print_daemon`
(`media_kind`/`media_width_mm`/`media_length_mm`/`media_name`, V34) and shown read-only in the UI.
Changing the roll needs no user action. An earlier manual "tape width" setting was removed
precisely because it could not describe die-cut labels and went stale.

**Print geometry is measured, not derived** (`internal/qlraster/raster.go` constants). These are
physical printer properties that cannot be computed from the media size:

| constant | value (QL-710W) | how determined |
|---|---|---|
| `mediaOffsetDots` | `0` — **left-aligned** | printed bands from different head zones; only dots 0–200 landed on a 17mm label |
| `dieCutLeadMm` | `6.0` | measured gap before content on a full-width test print |
| `unprintableEdgeMm` | `2.0` | measured blank strip across the head |

Media is **left-aligned on the 720-dot head, not centred**. Centring put a 17mm label's content at
dots 280–440 — off the label — giving correctly-sized, correctly-cut, *blank* labels. Die-cut jobs
must emit exactly `printableLines(lengthMm)` lines; too few and the printer cuts short (400 lines
on a 54mm label cut at 44mm), too many overruns. `TestDieCutGeometryMatchesHardware` locks all of
this in.

Also easy to get wrong (all previously were): `ESC i M` is **various mode settings** (`0x40` =
auto-cut), *not* compression; compression is the standalone `M` command (`0x4D 0x00` = none) and
must be sent or the printer decodes raster with leftover state; `ESC i K` `0x08` = cut at end.

### Adding another printer type or label type

- **Another label size on a Brother QL**: usually nothing to do — size comes from IPP. Verify with
  a full-width black test print; if it is misaligned, re-measure the three geometry constants.
- **A non-Brother printer**: `internal/ipp` is vendor-neutral and should be reused for status/media.
  The raster encoding is Brother-specific — introduce a printer-driver abstraction (e.g. an
  interface with `BuildCommands(png, media)` + a transport) and select on the IPP-reported
  make/model rather than adding conditionals inside `qlraster`.
- **Geometry constants are mirrored** in `frontend/src/utils/labelPrint.ts` (`labelSizeFor` renders
  to the *printable* area so nothing clips) — keep the two in sync, as with `MetricUnitParser`/
  `units.ts`.
- **Diagnosing a printer**: `clele-print-daemon status --printer-ip <ip>` prints state, media and
  the raw IPP media keyword. For alignment problems, print a full-width black label and measure the
  margins — that is how the current constants were established (see `daemon/README.md`).

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
  - **Local-match first**: before hitting the Internet, the typed term is fuzzy-matched against
    existing parts by part number (`GET /api/parts/local-match?q=` → `PartRepository.fuzzyByPartNumber`,
    pg_trgm similarity + substring, top 10). If any local parts match, they're shown with a
    "Use this · add stock" action that navigates straight to the part detail page (to add stock);
    only when there's no local match — or the user picks "Search the Internet instead" — does the
    AI/online search run
  - AI returns specs keyed by each spec definition's `jsonName` → auto-fills spec fields in the confirm step
  - Image picker fetches suggestions via DuckDuckGo, displays through backend proxy, uploads selected images as multipart blobs (client-side fetch + multipart upload to avoid Cloudflare/CORS issues)
  - Shows error feedback if image uploads fail (with link to navigate to saved part)
  - Location is **required** (the submit button is disabled until one is picked) and pre-selects the
    user's last-used location (`AuthUser.lastLocationId`); after a successful add the backend records
    the chosen location as the new last-used and the SPA calls `useAuth().refresh()` — see Locations
- **Part attachments**: one `part_attachment` bytea table holds three kinds of binary content per
  part, keyed by `type` (PHOTO/DATASHEET/ATTACHMENT) — see Part Attachments below. Photos: PNG-
  normalized, max 5. Datasheets & user attachments: original bytes + filename + content-type, uncapped
- **Spec definitions**: configurable specification fields (text, number, boolean, select) with units; can be associated with categories
  - Each definition has a `jsonName` (the exact key stored inside `part.specs`) separate from its
    human-readable `name`/title. All matching (AI prompt, Quick Add, Parts edit, Part detail) keys off `jsonName`
  - **Metric-prefix scaling** (`metricPrefix` flag, NUMBER + single unit): the stored value is in the
    base SI unit (the `unit`, e.g. `A`/`V`/`m`); display and edit forms scale it with the appropriate
    SI prefix (stored `0.009` → shown `9 mA`; the edit field is a mantissa input + prefix dropdown that
    converts back to the base unit on save). Stored values are unchanged — purely a display layer.
    Frontend logic lives in `utils/units.ts` + the shared `components/MetricNumberField.tsx`; the
    Spec Fields form exposes a "Scale with metric prefixes" checkbox. Leave it off for non-scalable
    units (°C, %, dB, counts). Mutually exclusive with the comma-separated multi-unit selector
  - **Convert TEXT → NUMBER** (`POST /api/spec-definitions/{id}/convert-to-number`, `PARTS_EDIT`; "→
    Number" row action on TEXT specs): parses every part's value for the spec — `<number>[<prefix>]<unit>`
    strings like `"9 mA"`/`"3.3V"`/`"100nF"` — into a chosen **principal (base) unit** (`"9 mA"` → `0.009`
    in base `A`) and rewrites `part.specs`. The request carries `{unit, metricPrefix, overrides, commit}`;
    a **dry-run** (`commit:false`) returns `{total, converted, suggestedUnit, failures:[{value,count}]}`
    (failures grouped by distinct value); `overrides` (original value → replacement) let the user fix
    unparseable values, and **commit is blocked server-side until 0 failures**. On commit it sets the spec
    to NUMBER + `unit` + `metricPrefix`. A half-open Partsbox range with no lower bound (`null..X`)
    auto-collapses to its single value `X`; other ranges (`X..Y`, `X..null`) stay failures to fix by hand.
    A blank unit scan only suggests a unit (no failures reported). Java parsing lives in
    `service/MetricUnitParser.java`, which
    **mirrors the prefix table in `frontend/src/utils/units.ts`** (keep the two in sync). UI: the
    `components/ConvertToNumberModal.tsx` modal (unit field pre-filled from `suggestedUnit`, editable
    failures list, Scan/Rescan/Convert)
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
- **Label printing**: per-user choice of the browser print dialog or silent printing via an
  installed network daemon, configured on My Account (see Label Printing above)

## API Endpoints (all under /api)

- `POST /auth/login`, `POST /auth/logout`, `GET /auth/me` — session auth (`/auth/login` and
  `/settings` are the only unauthenticated `/api` endpoints); `/auth/me` includes `hasOctopartCredentials`
- `GET /settings` — app-wide settings (currency); **public** (see App Settings)
- `GET/PUT /profile/octopart` — self-service: current user's OctoPart (Nexar) credentials
  (authenticated; secret never returned)
- `GET/PUT /profile/printing` — current user's print method + preferred daemon (authenticated)
- `GET /profile/print-daemons`, `POST /profile/print-daemons/{id}/claim`,
  `PUT/DELETE /profile/print-daemons/{id}` — daemon pairing/config; the list is filtered to the
  caller's own daemons seen at the browser's current IP (authenticated)
- `POST /print-jobs`, `GET /print-jobs/{id}` — enqueue a label print job / poll its status
  (authenticated)
- **Daemon-facing** (`/api/daemon/**`, API-key auth via `X-Daemon-Id`/`X-Daemon-Key`, *not* the
  session cookie): `POST /daemon/register` (public), `GET /daemon/jobs/next?wait=` (long-poll;
  always returns `X-Printer-Ip`), `POST /daemon/jobs/{id}/complete`
- `GET /downloads/clele-print-daemon.tar.gz` — the built daemon (static resource, not `/api`)
- `GET /organisations/selectable` — organisations the caller may switch into (authenticated);
  `GET/POST /organisations`, `GET/PUT/DELETE /organisations/{id}` — organisation management
  (requires `GLOBAL_ADMIN`); `PUT /profile/organisation` `{organisationId}` — switch the
  organisation in force for this session (authenticated). See Organisations above
- `GET /users`, `GET /users/{id}` — the **members of the current organisation** (requires
  `ORG_ADMIN`); `PUT /users/{id}/permissions` — set a member's permissions in the current
  organisation only (`ORG_ADMIN`); `POST /users/members` `{email}` / `DELETE /users/members/{id}` —
  add an existing account to / remove it from the current organisation (`ORG_ADMIN`);
  `POST /users`, `PUT/DELETE /users/{id}` — create, edit and delete the **account** itself
  (requires `GLOBAL_ADMIN`: an email is unique across the installation)
- `GET/POST /parts`, `GET/PUT/DELETE /parts/{id}` (mutations require `PARTS_EDIT`)
  - `GET /parts?search=&categoryId=&sort=` — search runs in the DB: `search` matches name /
    part_number (case-insensitive substring) + description (PostgreSQL full-text,
    `websearch_to_tsquery`); `categoryId` matches the category **and all descendants** (recursive
    CTE over `parent_id`); `sort` is `partNumber` (default) or `manufacturer`. The Parts page only
    fetches results once a search/filter is applied (it does not list the whole catalogue on load).
- `GET /parts/local-match?q=` — fuzzy-match existing parts by part number (pg_trgm), used by Quick
  Add to find an already-catalogued part before searching the Internet (authenticated)
- `DELETE /parts/by-user/{userId}` — delete every part created by a user, with its stock entries,
  images and movements; returns `{deleted: n}` (requires `USERS_EDIT`)
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
  `GET /locations/mine` (current user's own, for stock pickers); `POST /locations/{id}/merge`
  (`{targetId}`) moves the location's stock into another location and deletes the source
- `GET/POST /stock-entries`, `GET/PUT/DELETE /stock-entries/{id}`; `POST /stock/{add,take,move}` are
  the Part Detail stock verbs (add / take / move-between-locations, move's destination may be any
  user's location); `POST /stock/reconcile` realigns every stock entry to its ledger sum (requires
  `PARTS_EDIT`)
- `GET /stock-thresholds?partId=` — all thresholds (optionally filtered by part) with subtree totals;
  `GET /stock-thresholds/low` — thresholds where subtree total < minimum (drives Low Stock page);
  `POST /stock-thresholds` → upsert (create or update) a threshold, 201; location must be a root
  (400 otherwise); `DELETE /stock-thresholds/{id}` → 204
- `GET /dashboard`
- `GET /parts-search?q=` — AI part search
- `GET /parts-search/images?q=` — image suggestions
- `GET /image-proxy?url=` — external image proxy
- `GET/POST /spec-definitions`, `PUT/DELETE /spec-definitions/{id}`, `POST /spec-definitions/rescan`;
  `POST /spec-definitions/{id}/convert-to-number` converts a TEXT spec to NUMBER, parsing part values into
  a base unit (dry-run unless `commit:true`; requires `PARTS_EDIT`)
- Swagger UI at `http://localhost:8080/swagger-ui.html`
