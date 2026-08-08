# Clele — Electronic Parts Stock Management

Full-stack web app for managing electronic component inventory with AI-powered part lookup.

## Naming — "Clele" is internal, "Sortiment" is public

**"Clele" is the code name only.** It is fine — and permanent — in package names
(`com.clele.parts`), the repository, the database, config keys, file paths and log output. It must
**never** appear in anything a user reads: screen text, page titles, mail subjects and bodies,
error messages shown in the UI, or the mail sender name. Those all say **Sortiment**.

The backend holds it once, as `app.public-name` (`AppProperties.publicName`, default "Sortiment") —
mail templates read it from there rather than hardcoding a name. The frontend uses the literal
"Sortiment" (sidebar, login, `index.html` title). When writing any new user-facing string, check
which name you are using.

## Git — commit straight to `master`

This is a single-maintainer project and `master` is the working branch. **Commit directly to
`master`; do not create a branch first.** The default "branch before committing" habit only leaves
work stranded on branches nobody merges. Feature branches are fine when the user asks for one, or
for something genuinely long-running — but they are the exception, not the default.

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
                  LowStock, QuickAdd, SpecDefinitions, Users, Profile, Login,
                  Projects, ProjectDetail, ProjectBom (BOM import + matching)
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
    Parts description search (superseded by V43, which drops it)
  - V10 adds the `app_user` table (note: `user` is reserved in PostgreSQL) + `app_user_permission`
    child table, and seeds a bootstrap admin (see Authentication below)
  - V11 added `spec_definition.major_type` (display grouping — replaced by spec groups in V40);
    V12 adds location ownership
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
  - V38 deletes the `USERS_EDIT` permission rows from both permission tables. It granted nothing
    once V37 gated the member list, membership and permission editing on `ORG_ADMIN`, and V37 had
    already given `ORG_ADMIN` to every holder — so nothing is lost
  - V40 replaces `spec_definition.major_type` with **spec groups** and adds **spec aliases**:
    `spec_group` (per organisation) + `spec_definition.group_id NOT NULL`, seeded so the three
    MAJOR_TYPE values become each organisation's first groups (Dimensions/Technical/Physical), and
    `spec_alias(spec_definition_id, organisation_id, json_name)` unique per organisation — the
    alternate JSON names one spec is known by at its various sources (see Spec Groups & Aliases)
  - V41 lays a real taxonomy over the spec fields: **19 groups** (Dimensions, Package & Mounting,
    Materials & Finish, Power Supply, Voltage & Current Ratings, Passive Values, Semiconductor
    Characteristics, Analog & Amplifier, Data Conversion, Logic & Digital, Processor & Memory,
    Interfaces & Channels, Timing & Frequency, Optoelectronics, Switches/Relays & Connectors,
    Protection & Isolation, Thermal & Environmental, Compliance & Lifecycle, General) created per
    organisation, with all 355 catalogued `json_name`s assigned by hand. V40 could only carry over
    what `major_type` knew, which left ~290 unrelated fields in one "Technical" pile; V41 empties
    and drops Technical/Physical. It moves **only listed json_names**, so fields added since (and
    another organisation's own) keep their group, and it is **re-runnable** (upsert + idempotent
    update + conditional delete)
  - V42 merges the spec definitions that were the same spec under a different source name — 24 per
    organisation folded into 20 survivors (RDS(on) held as `rdsonmax`/`on_stateresistance`/
    `draintosourceresistance`, IFSM as three names, VCEO/VDSS/VGS doublets, `gender`->`contactgender`
    ...). It is the SQL equivalent of `SpecDefinitionService.merge`: aliases the source names, re-keys
    part values (target's own non-blank value wins), re-points `category_spec`, drops the source.
    Only **same-data_type** pairs are merged - a TEXT spec carries Partsbox ranges ("3..16") and a
    NUMBER spec a scalar, so the cross-type near-duplicates (`operatingsupplyvoltage`/`supplyvoltage`,
    `powerconsumption`/`powerdissipation`, `reversevoltage_dc_`/`reversevoltage`, `dielectric`/
    `dielectricmaterial`, `breakdownvoltage`/`reversebreakdownvoltage`) are left for the UI after a
    convert-to-number. Re-runnable
  - V39 adds `organisation_invitation` (+ `organisation_invitation_permission`): an Organisation
    Admin no longer adds members directly, they **invite** an email address (see Invitations below)
  - V43 brings `part.details` and `part.specs` into the Parts free-text search: it drops V9's
    description-only index and creates `idx_part_search_fts`, a GIN index over the **concatenation**
    `to_tsvector(description) || to_tsvector(details) || jsonb_to_tsvector(specs, '["string"]')`
    (see Parts search below)
  - V44 adds **BOM import**: `project_bom` (one per project — unique `project_id` — holding the
    uploaded file, its column mapping and who imported it) + `project_bom_line` (one row per line
    of the file, unique per `(bom_id, reference_key)`, carrying both what the file said and the
    match decision made about it), plus a trigram index on `part.mpn` so a BOM keyed on the
    manufacturer part number is fuzzy-matchable the way `part_number` has been since V15. Neither
    new table carries `organisation_id` — they reach it through `project_id` (see BOM Import below)
  - V45 adds **part kit templates**: `part_kit_template` (per organisation, unique `name`, holding
    every part field as a *text template* plus the specs JSONB) + `part_kit_template_value` (the
    values it varies over, unique per template) + `part_kit_template_tag` (tag **names**, resolved
    at generate time). Every column is TEXT because it holds a template, not a value (see Part Kit
    Templates below)
  - V46 makes attachments **shareable between parts**: `part_attachment` keeps the content and the
    new `part_attachment_link(part_id, attachment_id, display_order)` records which parts use it
    (`part_id`/`display_order` move there). It also adds `description` (the part number of the first
    part the attachment was used for, never updated), `md5_hash` (backfilled with PostgreSQL's
    `md5(data)`, deliberately **not** unique) and `organisation_id` — with several parts on one row
    the tenant can no longer be derived through `part_id` (see Part Attachments below)
  - V47 collapses the attachments V46's hashes exposed as byte-identical copies of each other:
    **403 of 955 rows** on the development catalogue (98 groups, ~42%) — the Partsbox import fetched
    the same product photo for every part it appeared on. Each group keeps its lowest id, which is
    also its earliest, so the survivor's `description` still names the first part the content was
    used for; every link is re-pointed at it (dropping the extra link where one part held the same
    file twice), then `display_order` is re-sequenced. No part loses a photo or a document; the
    discarded ids stop resolving, which shows only as a stale browser cache missing once
  - V48 deletes the stored `DATASHEET` rows that are **not PDFs** — HTML landing pages a vendor
    served with HTTP 200 where a document used to be, saved before `uploadFromUrl` checked (two
    167-byte "301 Moved Permanently" interstitials in the development catalogue). The condition
    mirrors `util/PdfBytes.looksLikePdf` (`%PDF` within the first 1024 bytes, not only at offset 0),
    the links go with them through the cascade, and `display_order` is re-sequenced.
    `part.datasheet_url` is left alone — it is still the canonical link, and re-fetching it is what
    "Download from URL" and the re-sourcing tool are for. PHOTO rows are not judged (ImageIO
    validated them on the way in) and neither are ATTACHMENTs, which are whatever the user says
- `ddl-auto: validate` — every schema change requires a new Flyway migration. The next free version
  is **V49** (always check `db/migration/` for the real high-water mark before adding one)
- ⚠️ **Flyway reads `${…}` in a migration as its own placeholder** and fails the whole migration on
  an unknown name ("No value provided for placeholder"). It applies to comments too — V45 documents
  the kit placeholder in prose rather than spelling it, and cost one failed boot to discover
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
    screen), seeing the organisation's members, **inviting** users to it, removing them, and setting
    their permissions **within it**. This is the *only* permission that grants any of that — a
    separate `USERS_EDIT` ("invite users") existed briefly but granted nothing and was dropped in
    V38. Note what it does *not* grant: creating or editing an account, or attaching an existing
    one — see Invitations below
  - `PARTS_EDIT` — "Add/edit parts"
  - `GLOBAL_ADMIN` — **global**: add/edit organisations and user accounts, switch into any
    organisation (including the template), and implicitly hold **every** per-organisation
    permission everywhere. That implication is what makes a newly created, memberless organisation
    usable at all (`AppUser.permissionsIn`)
- **Authorities are recomputed from the DB on every request**, for the organisation in force, by
  `config/OrganisationAuthoritiesFilter` (registered `addFilterAfter(SecurityContextHolderFilter)`
  in the session chain, so it runs after the context is loaded and before authorization). It sets
  `global + permissionsIn(currentOrg)` on the `SecurityContextHolder` and deliberately **does not**
  save the context back — the authority set is derived state, valid for one request and one
  organisation; persisting it would re-freeze it. **This is what keeps every existing
  `@PreAuthorize("hasAuthority('…')")` working unchanged**, and what makes permission edits take
  effect immediately rather than at the target user's next login.
  - `AppUserDetailsService` still grants only the *global* permissions at authentication time — the
    per-organisation set is unknown until an organisation is in force.
  - `service/PermissionService.applyAuthorities` re-issues and re-saves the `Authentication`
    (`SecurityContextRepository.saveContext` — required in Spring Security 6, mutating the held
    context is not persisted). It is still called at login (`AuthController`) and on every switch
    (`ProfileController`) because those requests build their response *after* the filter has run.
  - **Why the filter exists**: authorities used to be frozen in the session, which lives for a
    7-day sliding window. A permission granted, revoked, or *introduced by a migration* stayed
    invisible until the user next logged in — sessions created before V37 could never carry
    `ORG_ADMIN`, so their holders were denied the very screens they administer while `/auth/me`
    (which reads permissions live) showed them the navigation. Anything deriving access from the
    session rather than the database will reintroduce this.
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
  - All `/api/**` requires an authenticated session **except** `/api/auth/login`, `/api/settings`,
    `/api/invitations/token/**` (answering an invitation — see Invitations) and swagger / api-docs. Static SPA assets + the client-router fallback are public.
  - Specific mutations are gated with method security (`@EnableMethodSecurity` +
    `@PreAuthorize("hasAuthority('…')")`): part mutations (create/update/delete, image
    upload/from-url/delete, quick-add, auto-categorize, OctoPart search/apply) require `PARTS_EDIT`;
    all `/api/users` endpoints require `ORG_ADMIN` (except account create/edit/delete, which require
    `GLOBAL_ADMIN`). `/api/profile/**` (self-service settings) and
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
- **`SecurityConfig` is `@ConditionalOnWebApplication(SERVLET)`; the beans live in
  `SecurityBeansConfig`.** `@EnableWebSecurity` and the `MvcRequestMatcher`-based filter chains need
  Spring MVC, which is absent under the CLI profiles that set `web-application-type: none` (`import`,
  `datasheets`) — without the guard those runners die at startup on a missing
  `mvcHandlerMappingIntrospector`, which is what silently broke the documented Partsbox import
  command. `PasswordEncoder`, `SecurityContextRepository` and `AuthenticationManager` are therefore
  held in a separate always-on `SecurityBeansConfig`: none of them touch MVC, and all are needed in a
  non-web context (the first three by `InvitationService`/`PrintDaemonService`/`AdminUserService` and
  `PermissionService`; `AuthenticationManager` by `AuthController`, which is still component-scanned
  with no web server since `@RestController` is a `@Component` and only the MVC *infrastructure*
  disappears). Adding a bean to `SecurityConfig` that a plain `@Service` depends on will break the
  CLI profiles again.
- **Bootstrap admin** (seeded by migration V10): `admin@clele.local` / `admin` with both permissions.
  **Change this password after first login** (via the Users screen). To regenerate the seed hash use a
  BCrypt hash of the new password (Spring's `BCryptPasswordEncoder`, or `htpasswd -bnBC 10 "" <pw>`).

## Datasheet Preflight & Backfill

CLI tool that downloads the PDFs `part.datasheet_url` points at into `part_attachment`, and reports
whether their specs are actually machine-readable — the groundwork for extracting spec values from
datasheets (the licensing-clean path; see *Part metadata sources* below).

- Package `com.clele.parts`: `imports/DatasheetBackfillRunner` (`ApplicationRunner`, active only
  under the **`datasheets`** profile) + `service/DatasheetBackfillService` +
  `service/DatasheetAnalyzer`. `application-datasheets.yml` sets `web-application-type: none`, so
  the process runs and exits.
- **One pass with a `dryRun` switch, not a separate probe and fetch.** Telling a usable datasheet
  from a scan means parsing it, so a preflight that did not download would have nothing to classify.
  Dry run analyses and discards; a real run stores the same bytes as a `DATASHEET` attachment.
  ```
  cd backend
  # preflight (default — writes nothing):
  mvn21 spring-boot:run -Dspring-boot.run.profiles=datasheets \
    -Dspring-boot.run.arguments=--datasheets.limit=50
  # backfill:
  mvn21 spring-boot:run -Dspring-boot.run.profiles=datasheets \
    -Dspring-boot.run.arguments=--datasheets.dry-run=false
  ```
  Options: `--datasheets.dry-run` (default true), `--datasheets.limit` (0 = all),
  `--datasheets.delay-ms` (default 250), `--datasheets.report` (CSV path).
- **Resumable**: `PartRepository.findWithUndownloadedDatasheet` only returns parts with no
  `DATASHEET` attachment yet, and each part commits on its own (`JpaRepository.save` is itself
  transactional — the service is deliberately *not* `@Transactional`, since `store` is called via
  self-invocation and the annotation would be inert).
- **`DatasheetAnalyzer` routes each PDF to `TEXT`, `IMAGE_TABLES` or `NO_TEXT_LAYER`** by asking
  whether the text layer contains a **parametric section heading** ("Absolute Maximum Ratings",
  "Limiting Values", "DC Characteristics", …), *not* by measuring text volume. The failure mode in
  this catalogue is the hybrid PDF — a modern text layer holding the title block and packaging
  tables, with every parametric table pasted in as a scanned image. Measured: Atmel AT28C16 (specs
  in text) 901 avg chars/page and 16% sparse pages; TI SN74LS174 (specs as images) 991 and 18% —
  indistinguishable by volume, cleanly separated by heading presence (5 and 10 hits vs 0).
  `SECTION_HEADINGS` is the calibration surface; phrases are whole ("maximum rating", never
  "maximum") so prose like "exceeds the maximum" does not score. `DatasheetAnalyzerTest` pins this.
- **`datasheetRestTemplate` is backed by Apache HttpClient, not the default
  `SimpleClientHttpRequestFactory`** — the JDK's `HttpURLConnection` underneath it silently refuses
  to follow a redirect that **changes protocol**, and most stored URLs are `http://` links that
  redirect to `https://`. On the default factory those return HTTP 200 with the redirect
  interstitial's HTML in the body, which looks exactly like a successful download of a non-PDF. This
  cost a whole preflight run reporting 0% usable before it was spotted; the other `RestTemplate`
  beans are untouched, so this is worth remembering if another bulk fetcher is added.
- **The URL corpus is the real constraint, not the PDFs.** ~98% of `datasheet_url` values came from
  the Partsbox import and point at Octopart, in two populations that behave completely differently:
  `http://datasheet.octopart.com/*.pdf` serves real PDFs, while `https://octopart.com/…/c1?t=<token>`
  are signed tracking links with expiring tokens that now 403 behind Cloudflare. The tracking links
  can only be replaced — see Re-sourcing below.
- **Measured over all 824 candidates** (2026-08-07): 464 usable (56%) — 296 `TEXT`, 60 `IMAGE_TABLES`,
  108 `NO_TEXT_LAYER` — and 360 failures, essentially all Octopart. 12,228 pages, 364 MB if stored.

### Re-sourcing dead URLs

`--datasheets.resource=true` replaces the datasheet URL of parts holding an Octopart tracking link
(`PartRepository.findWithDeadOctopartDatasheetUrl`). Result: **211 of 281 repaired**; the remaining
70 are 60 TI parts TI no longer hosts and 10 Analog Devices/Maxim parts with no resolver.

- **Search is not available in bulk.** DuckDuckGo answers automated searches with a CAPTCHA
  ("select all squares containing a duck") served as HTTP **202** — a 2xx, so a scraper parses it,
  finds nothing, and reports an empty search. See *Blocked vs. empty* below: the block is now
  detected and reported as such, but it is still a block — bulk searching does not work.
- **`VendorDatasheetUrls` asks the manufacturer directly instead**, which needs no search engine and
  returns the vendor's own document. It covers **Texas Instruments only** — right for this list
  (271/281) but *not general*: any other vendor falls through to the blocked search path. Rather than
  model TI's package codes it walks the part number back a character at a time and lets verification
  reject the misses, resolving `TLC274CN`→`tlc274`, `LM324PWR`→`lm324`, `LM1117DT-2.5/NOPB`→`lm1117`.
- **Verification is not optional and its rules are load-bearing.** TI answers an unknown part with
  HTTP 200 and an HTML landing page rather than a 404 (`sn74ls76a` does exactly that), and a
  datasheet for the wrong part would silently poison spec extraction. `DatasheetResourcingService.mentionOf`
  therefore requires the PDF's text to name the part, and two rules in it exist because their absence
  caused real corruption:
  - **Never trim a trailing digit.** Shortening `SN74163N` to `SN7416` matched TI's hex-inverter
    datasheet (which prints `SN7416` 35 times) and attached it to four unrelated counters and shift
    registers. A trailing letter is a package/revision code; a trailing digit is the identity.
  - **Match per word, never across whitespace.** Flattening the document into one punctuation-free
    string fused line-wrapped words and *invented* part numbers: `sn7417.pdf` ends a line with
    `SN7417` and starts the next with `4`, yielding a phantom `SN74174`. Punctuation is still dropped
    *inside* a word so `MC14-89P` matches `MC1489P`.

  Both cost recall (a digit-final suffix like `SN7402NE4`, and part numbers spaced out in a heading),
  which surfaces as an honest `NO_MATCH` rather than as bad data. `DatasheetResourcingServiceTest`
  pins both regressions using the actual text that fooled the first version.

### Blocked vs. empty — the datasheet search reports which

A search that was refused and a search that found nothing both return an empty list, and only the
first is worth retrying. `DuckDuckGoDatasheetService.search` therefore returns a `SearchResult`
(status + results + detail) instead of a bare list, and the distinction is carried all the way to
the user:

- **`SearchStatus`**: `OK` / `NO_RESULTS` / `BLOCKED` / `FAILED`. `classify(statusCode, body)` is
  static and package-private so it can be pinned against the real pages without the network —
  `src/test/resources/ddg/` holds the actual challenge page (HTTP 202, served to a `curl`
  user-agent) and a trimmed real result page.
- **What counts as blocked**: the challenge markers in the body (`anomaly-modal`, `anomaly.js`,
  `challenge-form`, "bots use DuckDuckGo"), HTTP 403/429, **any 2xx that is not 200 with no results
  section** (the challenge is a *success* status — that is what hid it), and — deliberately — a
  page we cannot parse at all. An unrecognised page is reported as a refusal rather than as "this
  part has no datasheet": guessing the optimistic reading is the bug being fixed.
- **Being cut off mid-paging is not fatal**: the refusal only decides the outcome while no
  candidates have been collected, so a block on page 3 still returns pages 1–2.
- **`GET /api/parts-search/datasheets` now returns an object**, not an array —
  `DatasheetSearchResponseDTO` (`results`, `source` WEB/AI/NONE, `webSearchStatus` including
  `SKIPPED` for `forceAi`, `detail`). The AI fallback is unchanged; what changed is that the web
  search's own outcome survives it. The Part Detail "Find datasheet" modal says "the web search was
  blocked by a bot check — it did not run out of results" instead of "no datasheets found", and
  notes when the listed links are AI suggestions that followed a block.
- **`DatasheetResourcingService`** reports a new outcome `SEARCH_BLOCKED`, separate from
  `NO_CANDIDATES`. In a re-sourcing report those two mean opposite things.

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

## Component Cache — the local part catalogue consulted before the Internet

A read-only snapshot of a distributor catalogue (585k parts, 8.6M typed attribute values) living in
the **same database** under `cc_*`, loaded by an external importer. Full schema in `CCSTRUCTURE.md`.
Package `com.clele.parts.catalog`: `ComponentCacheRepository` (every statement that touches `cc_*`)
+ `ComponentCacheService` (the only class that understands its conventions) + `CcUnits`.

**Adding a part now consults three sources, cheapest first**: the organisation's own catalogue
(`/parts/local-match`), then this cache, then the AI web search. The order is the whole point — the
last one costs 5–13¢ and several seconds per call, and for a mass-market part the first two usually
answer. Nothing else about Quick Add changed: a cache hit lands on the confirm step exactly as an AI
result does, and the ordinary quick-add create stores it (with the existing post-commit hook pulling
the datasheet PDF in behind it).

**Two calls, and neither writes.** `GET /component-cache/search?q=` returns enough to recognise a
part; `GET /component-cache/{lcsc}` returns the whole record mapped onto this app's fields and spec
keys. The result is applied through the paths that already exist — Quick Add's create, or
`POST /parts/{id}/ai-apply` for a part already in the catalogue ("Look up in cache" on Part Detail,
beside "Look up specs"). The cache is a *source*, like the AI lookup and the datasheet reader, and
sources propose.

- **Plain JDBC, not JPA.** The tables are not owned by Flyway and are not in `db/migration`; mapping
  them as entities would put them under `ddl-auto: validate`, so an installation without the
  snapshot would **fail to start** rather than simply doing without. `available()` probes lazily
  with a real `SELECT` (not `to_regclass`, which answers for a table the role cannot read) and
  remembers the answer; everything degrades to "no cache" — empty lists, a hidden button — never an
  error.
- ⚠️ **The snapshot is owned by a different role, so `partsuser` needs a `GRANT`.** Flyway runs as
  `partsuser` and cannot grant on tables it does not own, so this is a manual step as the owner:
  `GRANT SELECT ON cc_components, cc_component_attributes, cc_attribute_def, cc_attribute_value,
  cc_prices, cc_categories, cc_raw_categories, cc_import_meta, cc_category_attributes, cc_parts,
  cc_component_attribute_values TO partsuser;` (+ `GRANT EXECUTE ON FUNCTION cc_price_at(text,int)`).
  A missing grant is the likeliest way for this to be half-installed, which is what the probe
  detects.
- **Search is three capped strategies merged on the best score per part**, because none is enough
  alone: trigram over `mfr` (typo/suffix tolerant, so `IRF540N` finds `IRF540NPBF`), the LCSC code
  (`C8734` resembles nothing by similarity), and full text over part number + description (for
  "0402 X7R 100nF", where no part number resembles the query). An exact case-insensitive MPN is
  promoted to a flat 1.0 so it cannot be outranked by a longer number sharing more trigrams; ties
  break on stock, since several houses second-source the same MPN. Measured 8–260 ms warm.
  ⚠️ **The case-insensitive comparison sits *inside* the trigram branch**, applied to candidates it
  already found — written as a top-level `WHERE lower(mfr) = lower(:term)` it uses no index and
  seq-scans a 2 GB table on every search.

### Translating an attribute into a spec

The key is the source name **squashed** — lower-cased, non-alphanumerics dropped, so "Gain Bandwidth
Product" becomes `gainbandwidthproduct`, the shape every key in this app already has. It is then
resolved against the organisation's `spec_definition`s *and their aliases*; an unmatched key is kept
anyway, since "Rescan from parts" promotes survivors into real fields. Measured overlap: 140 of 1599
cache attribute names land on an existing definition by name alone, covering ~31% of attribute
*links* — the rest arrive as new keys, which is the intended growth path, not a gap.

⚠️ **`display` is the default and the bare number is the exception.** That is the opposite of what
the schema invites. The cache stores SI base units with no symbol (100 nF is `1e-7`, 1 mm is
`0.001`) while this app's NUMBER fields declare their own unit or none, so a number written across
that boundary is wrong by orders of magnitude with nothing to signal it. `display` is the vendor's
own rendering *with* its unit ("2.2 MHz", "4 KB", "-40.0 °C ~ 125.0 °C") — it can be imprecise, but
never wrong about magnitude. A number is written only when **all three** hold, and each prevents a
real failure:

| condition | what it prevents |
|---|---|
| exactly one slot | "1 V ~ 18 V, -18 V ~ -1 V" has four, and its `value_num` is whichever the vendor listed **first** — positional, not semantic. Storing 1 as the supply voltage is a lie the user cannot see |
| a NUMBER definition | a TEXT or SELECT field wants the readable form |
| a reconcilable unit | `MetricUnitParser.factorToBase(unit, siBase)` — "mm" against the `length` family scales by 1000, "°C" against it does not reconcile at all and must fall back |

With **no declared unit**, only `CcUnits.SCALE_FREE` families (counts, percentages, dB, °C, angles —
things nobody writes with an SI prefix) may be stored bare. `data_size` is deliberately **not** in
either table: 4 KB is 4096, not 4000, and a field with no declared unit cannot say which it means.
`CcUnits` covering a family is what licenses a conversion, so **leave a family out unless its base
unit is genuinely certain** — an over-eager entry is how a 4 KB memory becomes 4000.

Absent values (`display` = `"-"`, `value_text` = the *string* `"NaN"` — note `'NaN'::float8` is a
valid float that poisons comparisons) are dropped, as are the four attributes the component row
already carries as columns (Manufacturer, Package, Basic/Extended, Status). Both are **reported** in
`skipped` rather than vanishing, so "why is Package not in the specs?" has an answer — it became
`part.footprint`.

`AiApplyRequest` and `QuickAddRequest` gained **`footprint`** for this: `package` is a first-class
column on every cached row and the one source that reliably knows it, and there was previously no
create/apply path that could set it. `ComponentCacheServiceTest` pins every rule above.

## BOM Import & Matching

Upload an EDA tool's BOM export into a project, keep it in the database, and match its lines to
catalogue parts at leisure. Replaces adding sixty parts one search at a time on Project Detail.

**Matching is work the user stops and resumes**, which is the constraint the whole design answers
to: the file, every line and every decision are persisted, and an unmatched line is a normal state
rather than an error. Package `com.clele.parts.service.bom`.

- **CSV/TSV only, columns detected not hardcoded.** `BomFileParser` sniffs the delimiter (`,` `;`
  tab `|`) from the header row counting *outside quotes* — every grouped designator list
  (`"C1,C2,C3"`) is a quoted field full of the delimiter. `BomColumnMapper` maps normalised headers
  (lowercased, alphanumerics only, so "Mfr. Part #" == "mfrpartno") onto `BomColumnRole` via
  synonym sets. One generic parser covers KiCad, Eagle, Altium and distributor exports; a header
  nobody anticipated costs the user one dropdown, not a code change. KiCad's intermediate `.xml`
  netlist is deliberately **not** supported — same fields, second parser, no one exports it by
  preference.
  - The parser reads the header row **as an ordinary record**, not via `setHeader()`: Commons CSV
    rejects a duplicate or blank header outright and real exports carry both, so `dedupeHeaders`
    repairs them ("Description (2)", "Column 4") instead of refusing the file.
  - The UTF-8 **BOM is stripped**. Left in place it becomes part of the first header's name, so
    "Reference" silently stops matching its own synonym — one column fails to map and nothing says why.
  - "vendor"/"supplier" are deliberately **not** manufacturer synonyms — those name the distributor.
    Claiming them writes "JLCPCB" into the manufacturer of every line on the board.
- **One BOM per project; re-upload merges.** `ProjectBomImportService` pairs incoming lines against
  stored ones on `reference_key` (the designators normalised by `DesignatorKey`: uppercased, split,
  naturally sorted, rejoined — so "C3, C1,C2" and "C1,C2,C3" are one line), then pairs the leftovers
  on **value + footprint**, which is what carries a match across a re-numbered schematic (C7 → C12).
  Unpaired incoming lines are added; unclaimed stored lines are deleted and reported.
- **Auto-match accepts only an exact, unambiguous hit** on part number or MPN, case-insensitively,
  MPN tried before value, and only when exactly **one** part comes back. Fuzzy hits are offered as
  ranked suggestions and never auto-accepted — two part numbers differing by a package suffix are
  similar enough to accept and different enough to be the wrong part, which is how the datasheet
  re-sourcing work attached a hex inverter's datasheet to four counters. Auto-match runs on new
  lines and on lines still `UNMATCHED` (the catalogue may have gained the part), never over a
  `MANUAL` match or a user's PROVIDED/EXCLUDED decision.
- **Four line states** (`BomLineStatus`): `UNMATCHED` (the work queue) / `MATCHED` / `PROVIDED` (an
  uncatalogued commodity assumed on hand — a resistor from the drawer) / `EXCLUDED` (deliberately
  not fitted; set automatically from the file's DNP column, and settable by hand). PROVIDED and
  EXCLUDED are *decisions*, which is why a re-import leaves them alone.
- **`changed`** flags a line whose value or footprint moved while it was already matched. The match
  is kept — the user decides whether it is still right — and any edit to the line clears the flag.

⚠️ **An import is a dry run unless `commit=true`, and `ProjectBomImportService.preview` must
`detach` the stored lines before touching them.** The merge refreshes the loaded entities in place
and those are managed: left attached, Hibernate's dirty checking flushes every "would update" and
the preview silently *is* the import. This was measured against a running instance, not theorised —
a dry run moved a matched line's value and set its review flag. **`@Transactional(readOnly = true)`
did not stop it**: `spring.jpa.open-in-view` defaults to true, so the EntityManager is opened by the
OSIV interceptor and outlives the transaction that set the flush mode, with the dirty entities still
in its persistence context. Detaching does not depend on any of that. `preview` and `commit` are
also separate public methods rather than one with a flag, since self-invocation would leave the
annotation inert. `ProjectBomImportServiceTest` pins the detach — mocks cannot reproduce a flush, so
what is pinned is the mechanism that prevents it.

- **The imported BOM is separate from `project_part`.** `ProjectBomService.apply` (PLANNING only,
  same guard as `addBomEntry`) pushes the matched lines into `project_part` — the table Pull Stock
  and the build flow actually read. Several lines can resolve to the same part (two 100nF caps in
  different footprints), so quantities are **summed**: `project_part` is unique per (project, part).
  PROVIDED/EXCLUDED/UNMATCHED lines are skipped, and existing `project_part` rows no line accounts
  for are **reported, never deleted** — the imported BOM is not the only way parts get into a project.
  Keeping this an explicit step matters: matching is exploratory and half-finished for most of its
  life, while `project_part` is what the build runs on.
- **Suggestions are computed per line, on demand** (`PartRepository.fuzzyByPartNumberOrMpn`, a
  sibling of Quick Add's `fuzzyByPartNumber` that also covers `mpn` and returns the similarity as a
  score). Filling a column for a hundred-line BOM on load would be a hundred trigram queries for
  rows the user reads a handful of.
- **Frontend**: `pages/ProjectBom.tsx` at `/projects/:id/bom` — a full page, not a modal; sixty
  lines need the width. Filter chips per state (plus "Changed"), a text filter, and per line the
  designators/value/footprint, qty and whole-build need, matched part, on-hand and status. The
  import modal shows the detected mapping as editable selects over the file's real headers, the
  diff counts and a per-line preview before committing. The match modal shows the line, ranked
  suggestions with similarity and on-hand, a debounced catalogue search, and **Assume provided** /
  **Exclude** / **Add to catalogue** (which links to `/quick-add?q=` — QuickAdd reads `q` to
  pre-fill its search). Project Detail gains an *Imported BOM* card above the existing BOM card.
  Row tints use translucent colours (`bg-purple-500/10`), never `bg-purple-50` — a fixed light
  colour sits on top of the row in dark mode and washes the text out.

## Part Kit Templates

Parts are often bought in bulk as a kit: thirty resistors that share manufacturer, footprint,
tolerance and power rating and differ only in resistance. A **kit template** is the part form filled
in once, with the placeholder `${value}` wherever the varying value belongs, plus the list of
values. "Generate parts" expands the two into real parts with stock. Package: `PartKitTemplate` /
`PartKitTemplateValue` + `PartKitTemplateService` + `PartKitTemplateController`
(`/api/part-kit-templates`, class-level `PARTS_EDIT`).

- **Every stored field is TEXT, including the ones the part types more strictly.** `"10k"` is not a
  number and `"…/${value}.pdf"` is not a URL until it has been expanded, so the template columns
  cannot carry the part's own types. The generated part is what gets them. The same reasoning drives
  the UI: **the template editor renders every spec as a plain text input** whatever the definition's
  `dataType` is — a number field or a dropdown could not accept a placeholder at all.
- **The part number template must contain `${value}`** (400 otherwise, enforced in
  `validate` and mirrored as a disabled Save in the editor). Part numbers are unique per
  organisation, so a template whose part number does not vary would generate one part however many
  values it lists, silently piling every value's stock onto it.
- **Generating finds before it creates, and never rewrites a part it found.** A kit is bought more
  than once: the second pack must add stock to the parts already there, not fail on the unique part
  number and not overwrite a description someone has since corrected by hand. A template describes
  how a part is *born*, not what it must keep looking like. A new part's stock is `INITIAL`, an
  existing one's is `PURCHASE` — the same distinction the manual paths draw — and both movements
  name the template in their comment.
- The whole run is **one transaction**: a half-generated kit is worse than none, since nothing in
  the parts list says which values were reached.
- **Values are sent as the whole list**, and `applyValues` rewrites it by *reusing the rows that
  survive* rather than clearing and re-adding. `orphanRemoval` plus the unique `(template_id, value)`
  means a delete-then-insert of the same value inside one transaction can hit the constraint before
  the delete is flushed. Blanks and duplicates are dropped server-side, so the stored order cannot
  disagree with what the user saw.
- Specs land through `SpecDefinitionService.canonicalizeKeys` and tags through
  `TagService.resolveOrCreate`, exactly as every other intake path — a tag may hold `${value}` too.
- ⚠️ **Do not spell the placeholder in a migration.** `${…}` is Flyway's own placeholder syntax and
  an unknown name fails the migration, comments included.
- **Frontend**: `pages/PartKitTemplates.tsx` (`/part-kits`) lists the templates with **Generate
  parts** per row — a dialog asking quantity per value, location (pre-selected from the user's
  last-used) and an optional unit price, which then reports per value which part was created and
  which already existed. `pages/PartKitTemplateEdit.tsx` (`/part-kits/new`, `/part-kits/:id`) is the
  two-section editor: the part template on the left, and on the right the value list, where values
  are added through a small textarea — Enter adds, Shift+Enter is a newline, and a pasted list is
  split **one value per line**, since a kit's contents are normally copied out of a spreadsheet or
  a supplier's page rather than typed — and removed with their × but **never edited**, since editing
  one would silently orphan the part a previous run generated from it. A duplicate in a paste is
  skipped with a note naming it, not refused: losing the other twenty-nine to one repeat would be
  the worse failure. A preview of what the first
  value produces sits under the list.

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
- The **currency**: `app.currency.code` (default `EUR`) + `app.currency.symbol` (default `€`).
  There is a single app-wide currency — prices are not stored with a currency.
- Also under `app.*` but **not** exposed to the SPA: `app.base-url` (`APP_BASE_URL`) and
  `app.mail.*` (from address, invitation expiry), used to build and send invitation mails — see
  Invitations.
- **Frontend**: `settings/SettingsContext` (`SettingsProvider` in `App.tsx`, wraps the routes) loads
  `/settings` once on mount with a sensible default (`€`) so prices render before/independent of the
  fetch. `useSettings()` exposes `settings` + `formatMoney(amount)` ("€ 12.34"); used wherever prices
  display (Dashboard stock value, Part Detail unit prices + total value, stock movements).

## Organisations (multi-tenancy)

- **The tenant boundary.** Every `part`, `category`, `location`, `spec_definition`, `tag` and
  `project` carries an `organisation_id` (V36), and so does `part_attachment` (V46 — it used to
  derive one through `part_id`, which stopped holding once several parts could share a row).
  `stock_entry`, `stock_movement`, `part_stock_threshold`, `part_attachment_link`, `project_part`,
  `project_stock`, `part_tag` and `category_spec` deliberately **do not** — they derive their
  organisation through `part_id`/`location_id`/`project_id`, so there is nothing that can drift out
  of sync.
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
- **Two user screens, deliberately separate.** `UserService`/`UserController` (`/api/users`, the
  **Users** screen) is organisation-scoped: it lists only members of the organisation in force and
  reports/edits only their permissions *there* — that is exactly an `ORG_ADMIN`'s reach.
  `AdminUserService`/`AdminUserController` (`/api/admin/users`, the **All Users** screen) is the
  `GLOBAL_ADMIN` view and crosses every boundary: all accounts, all memberships, all
  per-organisation permissions — and it is the **only** place an account is created, edited or
  deleted (`POST`/`PUT`/`DELETE /api/admin/users`; `POST /api/users` and `PUT/DELETE /api/users/{id}`
  were removed). They are not merged precisely because the first exists to *contain*
  an Organisation Admin, and one over-wide method in a shared controller would silently undo that.
  `AdminUserDTO` carries `memberships[]` (`UserMembershipDTO`: organisation + permissions +
  `implied`); `implied` is true when the permissions come from `GLOBAL_ADMIN` rather than stored
  grants, so the UI renders them read-only (editing them would change nothing).
  - Guardrails in `AdminUserService`: removing a membership also clears the permissions held there
    (otherwise re-adding silently restores access); the **last** organisation cannot be removed
    (409 — delete the account instead); and you cannot strip your **own** `GLOBAL_ADMIN`, since this
    screen is the only place it can be granted and the UI could not undo it; and you cannot delete
    your **own** account. `create` requires at least one organisation — an account in none can sign
    in and see nothing.
- **Frontend**: the switcher lives in the sidebar footer above the current user
  (`components/Layout.tsx`) and again on My Account; both **reload the page** after switching —
  every page fetches on mount, so only a full reload guarantees no stale cross-organisation data.
  `pages/Organisations.tsx` is the `GLOBAL_ADMIN` management screen; `pages/Users.tsx` lists members
  and invitations and holds the Invite dialog; `pages/AllUsers.tsx` is the installation-wide **All Users**
  screen (`GLOBAL_ADMIN`, route `/all-users`) — a table of every account with its organisations,
  and a per-user panel editing account details, global permissions, memberships and the permissions
  within each. Membership and permission changes save **per click** (one call each, since they are
  independent facts about different organisations); account details keep an explicit Save.

## Invitations

How an Organisation Admin brings someone in — and the **only** way they can. They cannot create an
account (an email is unique installation-wide, so that is `GLOBAL_ADMIN` on the All Users screen)
and they cannot attach an existing one by email either: that would let one organisation's admin
conscript another's user without their knowledge. They invite an address; the invitee decides.

- **`organisation_invitation`** (V39) holds email + organisation + inviting user + status
  (`InvitationStatus`: PENDING/ACCEPTED/DECLINED/REVOKED) + `expires_at`, with the permissions the
  invitee will hold on acceptance in `organisation_invitation_permission`. The `token` (32 random
  bytes, URL-safe base64) is the **whole credential** on the mailed link, hence single-use and
  expiring (`app.mail.invitation-expiry-days`, default 14).
- **Two controllers, deliberately split** the same way the two user screens are:
  `InvitationController` (`/api/invitations`, class-level `ORG_ADMIN`) is the inviting side —
  list / `lookup?email=` / create / revoke, all scoped to the organisation in force.
  `InvitationAccessController` (`/api/invitations/token/**`) is the invitee's side and is
  **`permitAll`** in `SecurityConfig`, because whoever follows the link may have no account at all.
  A public method inside the `ORG_ADMIN` controller would be one annotation away from a mistake.
- **`lookup`** answers "who is this address?" for the invite dialog (exists / full name / already a
  member / already invited) so the admin can see they are inviting the person they meant. It is
  readable by any Organisation Admin for an arbitrary address, so it reveals only the display name.
- **Accepting**: `InvitationService.accept` adds the membership and applies the invited permissions.
  If no account exists it creates one first, requiring full name, phone **and** a password (without
  one the account cannot log in). For an **existing** account the request body is *ignored entirely*
  — the token proves control of a mailbox, which is enough to add a membership and nowhere near
  enough to rewrite someone's name or password.
- **Mail is optional.** `MailService` composes the message and hands it to the configured provider
  (see Outgoing Mail below); with none configured it logs the link instead and reports
  `mailSent: false`, and the invite dialog then shows the link so the admin can pass it on. A send
  failure never fails the invitation — the row is valid and the link works. Set `APP_BASE_URL` when
  the app sits behind a proxy that rewrites the host — otherwise the link is derived from the
  request that created the invitation.
- **Frontend**: `pages/Users.tsx` has the **Invite user** dialog (email with a debounced
  `lookup` shown beside it, plus the per-organisation permission checkboxes) and a table of every
  invitation sent, with Withdraw on the outstanding ones. `pages/AcceptInvitation.tsx` is the
  invitee's page at the **public** route `/invite/:token` (registered outside `RequireAuth` in
  `App.tsx`); it asks for name/phone/password only when the invitation reports `newAccount`.

## Outgoing Mail

Mail delivery is behind a provider interface so the email service can be swapped **by
configuration, never by code**. Package `com.clele.parts.mail`:

- **`MailProvider`** — the API: `name()` (the config name), `isConfigured()`, `send(EmailMessage)`,
  throwing `MailSendException`. **`EmailMessage`** is the provider-neutral message (from + fromName,
  to, subject, text, optional html) — `EmailMessage.plain(...)` for the common case.
- **`MailProviderRegistry`** collects every `MailProvider` bean and returns the one named by
  `app.mail.provider`. An **unknown name fails at startup** — falling back silently would mean a
  typo sends mail through the wrong account, or not at all. `none` disables sending. A selected but
  *unconfigured* provider is not an error: `active()` returns empty and `MailService` logs the mail
  (including the invitation link) instead — that is what makes a fresh install and local dev work.
- **Implementations**: `SmtpMailProvider` (`smtp`, the default — Spring's `JavaMailSender`,
  configured under `spring.mail.*`; unconfigured while `spring.mail.host` is blank) and
  `MailerSendMailProvider` (`mailersend` — the MailerSend HTTP API, `POST {base-url}/email` with a
  Bearer token; **202 Accepted** means queued, and its rejection body is reported through verbatim
  because unverified-domain/suppression/quota errors are only fixable at MailerSend).
- **Adding a provider** = one `@Component implements MailProvider` + its settings under
  `app.mail.<name>`. Nothing else changes; `MailService` knows what a mail *says*, never how it
  travels.
- **Config** (`app.mail.*`): `provider` (`MAIL_PROVIDER`, default `smtp`), `from` (`MAIL_FROM`),
  `from-name` (`MAIL_FROM_NAME`), `invitation-expiry-days`, and
  `mailersend.api-key` (`MAILERSEND_API_KEY`) / `mailersend.base-url`. SMTP still takes
  `MAIL_HOST`/`MAIL_PORT`/`MAIL_USERNAME`/`MAIL_PASSWORD` under `spring.mail.*`.
  MailerSend requires the `from` address to be on a domain verified in the MailerSend account.

## Spec Groups & Aliases

Specifications come from several sources (Partsbox, OctoPart/Nexar, the AI lookup, hand entry), each
with its own JSON key for the same concept. Two mechanisms keep that manageable:

- **Groups** (`spec_group`, V40) are sets of related fields — "Power" holds supply voltage, average
  current draw, max driver current; "MCU Specs" holds RAM/Flash/EEPROM size and the interface counts.
  A spec belongs to **exactly one** (`spec_definition.group_id NOT NULL`), replacing the old fixed
  `major_type` buckets — whose three values (Dimensions/Technical/Physical) V40 seeds as every
  organisation's first groups. `SpecGroupService.defaultGroup()` is where a spec lands when the
  caller names no group ("Technical", else the first by display order, else one created on the spot),
  and it is what makes `rescanFromParts` and an API client that omits `groupId` work.
- **Aliases** (`spec_alias`, V40) are the alternate JSON names one spec is known by, unique per
  organisation exactly like `json_name`. They are what makes **merging** durable: folding `vsupply`
  into `supplyvoltage` keeps `vsupply` as an alias, so the source that keeps sending it still lands on
  the surviving spec instead of re-creating the duplicate. They can also be added by hand in the field
  editor, to register a source's naming before a duplicate ever appears.

**`SpecDefinitionService.canonicalizeKeys(specs)`** is the single resolution point: it rewrites an
incoming spec map's keys onto the canonical names, and is called on every path that stores specs from
outside (`PartService.buildPartFromRequest`, `applyOctopart`, `QuickAddService.createPart`). Unknown
keys pass through untouched — an unrecognised spec is still worth storing, and a rescan turns it into
a definition later. `rescanFromParts` skips keys that are an alias, or it would recreate exactly the
duplicate a merge removed.

**Merging** (`POST /spec-definitions/merge`, `PARTS_EDIT`) re-keys every part value from the sources
onto the target's JSON name (**the target's own value wins** where a part has both — it is the
definition the user chose to keep), moves the sources' names *and* their existing aliases onto the
target, then deletes the sources. Deleting a group is refused while it still holds fields, so no spec
is ever orphaned; the field-level "Move to group" is how you empty one.

**Frontend**: `pages/SpecDefinitions.tsx` (`/specs`) is now the **group** overview — name,
description, field count, plus the global "Rescan from parts". Opening a group navigates to
`pages/SpecGroupDetail.tsx` (`/specs/:groupId`), which holds the field table with checkbox
multi-select and the toolbar actions **Merge selected** (a radio picks which of the selected fields
survives) and **Move to group**, alongside the per-row → Number / Edit / Delete. The field editor has
the group selector and the comma-separated alias list. On **Part detail**, the Specifications card
renders one section per group in a 3-column grid, in the groups' display order, with values whose key
matches no definition collecting in a trailing "Other" section.

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
  "+ Sub"/Edit/Delete gated by `canManage`, now simply `PARTS_EDIT`-or-admin, and per-node stock
  figures — parts / on-hand / value — from `LocationRepository.locationStats`, rolled up over each
  node's subtree so a collapsed parent accounts for everything below it; the tooltip splits out what
  is held directly at the node) with a hierarchical
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
- **Bulk cleanup**: `DELETE /api/parts/by-user/{userId}` (`ORG_ADMIN`) →
  `PartService.deleteByUser` removes every part that user created plus its stock entries, images and
  movements, and returns the count. `stock_entry` has no `ON DELETE CASCADE`, so it is cleared first
  (`StockEntryRepository.deleteByPartIdIn`) before the bulk `Part` delete (`part_attachment` and
  `stock_movement` cascade at the DB). The Users page exposes a per-row **Delete parts** action.
- Note: `created_by_id` is a non-null FK with no cascade, so deleting a user who still has parts
  fails at the DB until their parts are removed. `deleteByUser` is scoped to the **current
  organisation**, so cleaning up a user in one organisation leaves their parts in others intact.

## Part Attachments

- A single `part_attachment` bytea table (entity `PartAttachment`, V19) stores all binary content,
  distinguished by `type` (`AttachmentType`: `PHOTO`, `DATASHEET`, `ATTACHMENT`). Columns: `data`
  (bytea), `type`, `content_type`, `filename` (NULL for photos), `description`, `md5_hash`,
  `organisation_id`, `created_at`.
- **Attachments are shared, not owned by one part** (V46). Which parts use a row lives in
  `part_attachment_link(part_id, attachment_id, display_order)` — entity `PartAttachmentLink`, both
  FKs `ON DELETE CASCADE`, unique per pair. That is what lets every value in a resistor kit show the
  same photo: they are the same picture, and storing thirty copies of it was the problem.
  `display_order` is per (part, type) and belongs to the link, since two parts may order their
  photos differently.
  - **`description` is the part number of the *first* part the attachment was used for and never
    changes.** Once several parts share a row, that is the only thing about its origin that stays
    true; re-pointing it at whichever part happens to be looked at would make it say nothing.
  - **`md5_hash` is what recognises content already held** — matched with `type` and
    `organisation_id` (the index `idx_part_attachment_hash` covers exactly that), then confirmed by
    comparing the bytes, because silently serving another part's document on a hash collision is the
    kind of wrong nobody notices. It is deliberately **not unique** — V47 collapsed the duplicates
    the import had already accumulated (403 of 955 rows), but a hash is a fingerprint, not a key:
    the same bytes may legitimately be held twice while a merge is pending, and a unique constraint
    would turn that into a failed upload rather than a duplicate to tidy up later.
  - **`organisation_id` is stored, not derived.** Every other per-part table reaches its tenant
    through `part_id`; with several parts on one row that derivation is gone, so sharing and hash
    matching are confined to one organisation explicitly.
- **`PartAttachmentService.store(partId, data, contentType, filename, type)` is the single write
  path** — `upload`, `uploadFromUrl` and the datasheet backfill all funnel through it, so nothing
  can add a row without going past the dedupe. It takes a part *id*, not the entity: the backfill
  runs outside a transaction with detached parts, whose lazy organisation could not be read.
- **`PartAttachmentService`** branches by type:
  - `PHOTO` — PNG-normalized via ImageIO (`convertToPng` / `downloadAndConvertToPng`), `content_type`
    `image/png`, no filename, **capped at 5 per part** (counted over the links). Normalizing before
    hashing is what makes the same photo re-uploaded as a JPEG match the stored PNG.
  - `DATASHEET` / `ATTACHMENT` — stored **as-is**: original bytes, original `content_type` and
    `filename`, **uncapped**. `uploadFromUrl(.., DATASHEET)` downloads the raw file (response
    content-type preserved, filename derived from the URL path) — used by the Part Detail
    "Download from URL" button to pull the part's `datasheet_url` PDF into storage.
  - `delete` **unlinks from this part** and drops the content only when the last link goes, then
    re-sequences `display_order` within the same part+type group. Deleting a part goes through
    `deleteAllForPart`; the bulk paths that delete parts straight through the database (a
    `deleteByUser` cleanup) rely on the link's `ON DELETE CASCADE` and must call `deleteOrphans()`
    afterwards, since the DB cannot know whether the content survived on another part.
- **`PartAttachmentController`** (`/api/parts/{partId}/attachments`): `GET` (optional `?type=`),
  `GET /{id}` serves bytes with the stored content-type (photos render inline with a 7-day cache;
  datasheets/attachments add `Content-Disposition: attachment; filename=…`), `POST` (multipart
  `file` + `type`), `POST /from-url` (`{url, type}`), `DELETE /{id}` (unlinks; see above). Mutations
  require `PARTS_EDIT`. The URLs are unchanged by sharing — a link is what authorises
  `/parts/{partId}/attachments/{id}`, so an attachment the part does not use is simply 404.
  `PartAttachmentDTO` carries `description`, `md5Hash` and `partCount` (how many parts use it); the
  Part Detail page marks a shared file with a **shared** badge and warns before removing one that
  the other parts keep it.
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

## Spec coverage — keeping newly added parts from arriving bare

The imported catalogue is fine; the risk is what gets added from here. See `SPECS.md` (untracked
working note) for the original analysis. What is built:

- **`part.specs` may hold keys no `spec_definition` covers, and that is deliberate.** The AI intake
  paths keep whatever the model returned so that "Rescan from parts" can promote the survivors into
  real fields — it is how the catalogue learns. Quick Add shows them under an **"Other"** heading on
  the confirm step; the part edit modal shows them under "Other" too.
- ⚠️ **`PartRequest.specsMode` (`MERGE` default / `REPLACE`) exists because of this.** A form that
  builds its fields from the definitions does not know every key the part carries, so setting
  `part.specs` wholesale from such a form **deletes** the undefined ones. `PartService.resolveSpecs`
  therefore merges by default: an omitted key is left alone, and a key sent null/blank is removed
  (that is how a merging client clears a field). **Send `REPLACE` only from a form that rendered
  every key** — today exactly one does, `PartEditModal`, which needs it for its per-row remove
  button to work at all. A client that forgets the flag can only fail to delete, never destroy.
- **Dashboard tile + Parts filter** count parts holding fewer than
  `PartRepository.SPARSE_SPEC_THRESHOLD` (5) spec keys — 336 of 1102 when added. The tile links to
  `/parts?sparse=1`; the flag must appear in `Parts.tsx`'s `hasCriteria` **and** `hasAdvanced`, or
  the link lands on a page that never fetches. The count is a `jsonb_object_keys` sub-select, not
  indexable as written and not worth indexing at this size. Note `coalesce(p.specs, '{}')` carries
  **no `::jsonb` cast** — Hibernate reads the `:` of `::` as a named parameter and mangles the SQL
  at runtime (`syntax error at or near ":"`); none is needed, since `p.specs` is already jsonb. Use
  `cast(x as jsonb)` if an explicit cast is ever unavoidable.

### Looking a part up after it exists

**"Look up specs"** on Part Detail runs the same `AiPartSearchService.search` the wizard uses and
applies the result through `POST /parts/{id}/ai-apply`. The button is **unconditional** for an
editor — no credentials, and no "already linked" guard like OctoPart's — because the hand-entered
part is exactly the one that needs it.

Unlike the OctoPart flow, which applies specs wholesale, each spec is confirmed individually,
because this runs on parts that already carry curated data. Returned specs are classified against
what the part holds: **new** (ticked by default), **differs** (listed, *not* ticked), **identical**
(not shown — nothing to decide). That is what "only fill empty fields" means here: a default, not a
mode. The suggested **category is shown but never applied** — the lookup returns a category *name*,
and resolving that to one of this organisation's categories is a separate, fuzzy problem.

`PartService.applyAiLookup` is a sibling of `applyOctopart`, not a flag on it: the OctoPart path
additionally sets the OctoPart link and requires an id, and one method with a sometimes-required id
is how the wrong field gets written.

### Reading the specs out of the datasheet

**"Get specs"** beside each stored datasheet on Part Detail reads that PDF and proposes specs plus a
functional description from it (`POST /parts/{id}/datasheet-extract?attachmentId=`,
`DatasheetSpecExtractionService`). It **writes nothing** — the result is confirmed field by field
and applied through the same `POST /parts/{id}/ai-apply` the web lookup uses.

**This is the cheap source and the licensing-clean one.** Measured on the same catalogue: a web
lookup of `L7809CD2T` cost 6.5¢ and returned 8 specs; reading the stored `at28c16.pdf` cost **1.6¢**
(12,028 in / 808 out, no web searches) and returned **19**. The difference is that ~89% of a web
lookup is the searches plus the results replayed through the tool loop, and this path runs none — the
document is already in the database. A manufacturer datasheet is also a published document about one
part rather than a compiled parametric database, so nothing restricts retaining what it says (see
*Part metadata sources*).

**It does not send the document.** A datasheet is tens of pages of pinouts, package outlines and
ordering tables; sending all of it would cost more than the lookup it undercuts.
`buildExcerpt` sends the **front matter** (page 1, plus page 2 when page 1 is barely more than a
title block) and a **6,000-character window from each parametric heading** `DatasheetAnalyzer` found,
with overlapping windows merged and the whole thing capped at 90,000 chars. Measured: a 12-page
datasheet reduces to 13,003 chars. Pages are marked `[page N]` in the excerpt and the model reports a
page per value, which the confirm modal shows beside it — a value you cannot trace is a value you
cannot defend when it turns out to be wrong. `DatasheetSpecExtractionServiceTest` pins the merging,
the cap and the front-matter fallback.

**The route decides whether it runs at all** (`DatasheetAnalyzer`, same classifier as the preflight):
- `TEXT` — what the feature is for; ~64% of the usable datasheets in this catalogue.
- `IMAGE_TABLES` — the parametric tables are pasted-in scans. It still runs, because the front matter
  is real text and yields a description, but the response carries the route and the modal says so.
  Measured on `sn74ls174.pdf`: 815 chars of excerpt, 2 specs and a good description, for 0.8¢.
  Reporting that as a complete read would be the same mistake as reporting a blocked web search as
  "no results".
- `NO_TEXT_LAYER` — a pure scan, **refused before spending anything**. There is no text to send and
  the vision path is not built.

Landing rules match every other intake path: keys go through `SpecDefinitionService.canonicalizeKeys`,
unknown keys are kept (a rescan promotes them later), and the confirm step ticks **new** values by
default while leaving **differing** ones unticked.

⚠️ **`DatasheetSpecExtractionService` takes `aiDocumentRestTemplate`, not the shared one.** The shared
`restTemplate` has a 30 s read timeout, which a 20k-token extraction routinely exceeds — and that does
not surface as "slow", it surfaces as a read failure on a request Anthropic already billed. Spelled as
an explicit constructor because `@RequiredArgsConstructor` drops the `@Qualifier` and would inject the
wrong bean silently (same trap as `PartAttachmentService`).

`AiApplyRequest` gained **`details`** for this path: the web lookup returns a one-line
`shortDescription` (→ `description`), while a datasheet carries several sentences of what the part
does (→ `details`). `SpecFieldCatalog` renders the spec-field list both prompts inject, so the two
describe the catalogue identically — a spec offered as `"100 nF"` in one prompt and a bare number in
the other would store two different values for the same field.

### Datasheets are fetched at creation

Quick Add pulls the part's `datasheetUrl` into `part_attachment` as a `DATASHEET` right after
creation — while the link is known good. That matters because a stored URL rots: ~98% of the
imported ones pointed at Octopart and most are now dead (see *Datasheet Preflight & Backfill*).

⚠️ **The fetch runs in the controller, after `quickAdd` has committed, not inside it.**
`QuickAddService.quickAdd` is `@Transactional` and `uploadFromUrl` is transactional too, so calling
it from inside would join that transaction: a failed download marks it rollback-only and the user
loses the part they just created **even though the exception was caught**. It would also hold a DB
connection open for the length of a vendor fetch. Same reasoning as the Partsbox importer, which
downloads images outside its load transaction. Failures are swallowed at INFO — an unreachable or
moved datasheet is an ordinary outcome, and the URL stays on the part so "Download from URL" can
retry by hand.

Two defects in `PartAttachmentService` had to be fixed first, both of which stored junk silently:

- It downloaded with the **default `restTemplate`**, whose `HttpURLConnection` refuses cross-protocol
  redirects — and most stored datasheet URLs are `http://` links redirecting to `https://`, which
  came back as HTTP 200 carrying the interstitial's HTML. It now takes
  `@Qualifier("datasheetRestTemplate")`, spelled as an explicit constructor because
  `@RequiredArgsConstructor` drops the qualifier and would inject the wrong bean silently.
- It stored the response **verbatim, with no PDF check**. A vendor answers a moved document with
  HTTP 200 and a landing page, not a 404 — `ti.com/product/LM317` returns 358 kB of `text/html` for
  a URL that reads like a document. `DATASHEET` uploads now go through `util/PdfBytes.looksLikePdf`
  (promoted out of `DatasheetAnalyzer`, which still uses it) and a non-PDF is refused. `ATTACHMENT`
  is unchecked — that one is whatever the user says it is.

**Expect nothing back for house-numbered and vintage parts.** Measured: `L7809CD2T` returned 8 specs
(6.5¢); the DEC PDP-11 board `M7093` returned zero (5.2¢). That is a correct answer, not a failure,
and the modal says so rather than telling the user to search again — "nothing searched yet" and
"searched, found nothing" are different facts. It also bounds the feature's reach: most of the
hand-entered backlog *is* DEC boards and unbranded modules.

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

## Key Features

- **CRUD** for parts, categories (hierarchical), locations, stock entries
- **User accounts & login** with permission-based UI gating + backend enforcement (see
  Authentication & Authorization above). Accounts are created and edited only by a Global
  Administrator (All Users screen); an Organisation Admin brings people in by **invitation** — a
  mailed accept/refuse link that creates the account if there is none (see Invitations)
- **Parts search screen**: searches on demand (name / part number / description + details +
  spec-value full-text), filters
  by category subtree, sortable by part number or manufacturer. A **"More search options"** panel
  under the search bar (collapsed by default, auto-opened when the restored URL uses it) adds
  personal-product-code / location / tags / manufacturer filters. All criteria — basic and advanced
  — live in one `Criteria` object mirrored in the URL query string (`q`/`cat`/`sort`/`pn`/`loc`/
  `mfr`/`tags`), so Back and reload restore the same result set. Nothing is fetched unless at least
  one criterion is set
- **Dashboard** with low stock alerts
- **Quick Add wizard** (3-step): AI part search → select result → confirm details + stock entry
  - **Three sources, cheapest first**: the organisation's own catalogue, then the local **component
    cache** (585k parts in `cc_*`, free and offline — see Component Cache above), then the AI web
    search. A cache hit pre-fills the confirm step exactly as an AI result does
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
- **Part attachments**: one `part_attachment` bytea table holds three kinds of binary content,
  keyed by `type` (PHOTO/DATASHEET/ATTACHMENT), **shared** by every part that links to it — see Part
  Attachments below. Photos: PNG-normalized, max 5 per part. Datasheets & user attachments: original
  bytes + filename + content-type, uncapped. Identical bytes are stored once and linked, so a kit of
  thirty resistor values carries one photo, not thirty copies
- **Spec definitions**: configurable specification fields (text, number, boolean, select) with units; can be associated with categories
  - Every definition belongs to exactly one **spec group** and may carry **aliases** — the other
    JSON names the same spec has at other sources (see Spec Groups & Aliases)
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
- **BOM import**: upload an EDA tool's CSV export into a project, then match its lines to catalogue
  parts a few at a time over as long as it takes — the file and every decision are stored, exact
  hits match themselves, and re-uploading a revised export merges rather than starting over (see
  BOM Import & Matching above)
- **Part kits**: define a pack bought as a set — a resistor kit, a capacitor assortment — as one
  part template plus the list of values it varies over, then generate every part with its stock in
  one action (see Part Kit Templates above)
- **Component cache**: a local snapshot of a distributor catalogue, consulted when adding a part
  before any web/AI lookup and available on Part Detail as "Look up in cache" — free, offline and
  instant (see Component Cache above)
- **Label printing**: per-user choice of the browser print dialog or silent printing via an
  installed network daemon, configured on My Account (see Label Printing above)

## API Endpoints (all under /api)

- `POST /auth/login`, `POST /auth/logout`, `GET /auth/me` — session auth (`/auth/login`,
  `/settings` and `/invitations/token/**` are the only unauthenticated `/api` endpoints); `/auth/me` includes `hasOctopartCredentials`
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
  organisation only (`ORG_ADMIN`); `DELETE /users/members/{id}` — remove a user from the current
  organisation (`ORG_ADMIN`). Adding a member is **not** here — it happens by invitation, and the
  account itself is managed under `/admin/users`
- `GET /invitations`, `GET /invitations/lookup?email=`, `POST /invitations`,
  `DELETE /invitations/{id}` — invite an address to the current organisation, all `ORG_ADMIN`;
  `GET /invitations/token/{token}`, `POST /invitations/token/{token}/accept`,
  `POST /invitations/token/{token}/decline` — the invitee's side, **unauthenticated** (the token is
  the credential). See Invitations above
- `GET /admin/users`, `GET /admin/users/{id}` — **every** account with **all** of its memberships;
  `POST /admin/users` (create, `organisationIds` required) / `DELETE /admin/users/{id}` (delete the
  account outright) — the only place accounts are created and deleted;
  `PUT /admin/users/{id}` — account details + global permissions;
  `POST /admin/users/{id}/organisations` `{organisationId}` /
  `DELETE /admin/users/{id}/organisations/{organisationId}` — membership;
  `PUT /admin/users/{id}/organisations/{organisationId}/permissions` `{permissions}` — permissions
  in one named organisation. All `GLOBAL_ADMIN` (see All Users below)
- `GET/POST /parts`, `GET/PUT/DELETE /parts/{id}` (mutations require `PARTS_EDIT`)
  - `GET /parts?search=&categoryId=&sort=&personalNumber=&manufacturer=&locationId=&sparseSpecs=&tags=` — search
    runs in the DB: `search` matches name / part_number (case-insensitive substring) + description
    **plus `details` and the string values in `part.specs`** (PostgreSQL full-text,
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
    *Spec coverage* below), and `tags`
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
  writes. See Component Cache above
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
- **Part kit templates** (`/part-kit-templates`, all `PARTS_EDIT` — see Part Kit Templates above):
  `GET` / `GET /{id}` / `POST` / `PUT /{id}` (the whole template including its value list) /
  `DELETE /{id}` (the parts it generated are untouched); `POST /{id}/generate`
  `{quantityPerValue, locationId, unitPrice?}` creates or finds one part per value and adds stock
  to each, returning per value which it was
- **Project BOM import** (`/projects/{projectId}/bom`, all `PARTS_EDIT` — see BOM Import above):
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
  the datasheet reader, which is why it carries `details`. See *Looking a part up after it exists* below
- `POST /parts/{id}/datasheet-extract?attachmentId=` — read a datasheet already stored on the part and
  propose specs + a description from it (`DatasheetExtractionDTO`); writes nothing, ~1.6¢, no web
  search. `attachmentId` optional (defaults to the part's first datasheet). Requires `PARTS_EDIT`.
  See *Reading the specs out of the datasheet* below
- `GET /parts-search/images?q=` — image suggestions (requires `PARTS_EDIT`)
- `GET /parts-search/datasheets?q=&forceAi=` — datasheet links, web search first and AI as fallback
  (requires `PARTS_EDIT`).
  Returns `DatasheetSearchResponseDTO` (results **plus** the web search's outcome) — see *Blocked vs.
  empty* above
- `GET /image-proxy?url=` — external image proxy
- `GET/POST /spec-groups`, `GET/PUT/DELETE /spec-groups/{id}`, `GET /spec-groups/{id}/spec-definitions`
  — spec groups and the fields inside one (see Spec Groups & Aliases)
- `POST /spec-definitions/merge` `{targetId, sourceIds}` folds duplicate spec fields into one
  (`PARTS_EDIT`); `POST /spec-definitions/move` `{specIds, groupId}` moves fields between groups
- `GET/POST /spec-definitions`, `PUT/DELETE /spec-definitions/{id}`, `POST /spec-definitions/rescan`;
  `POST /spec-definitions/{id}/convert-to-number` converts a TEXT spec to NUMBER, parsing part values into
  a base unit (dry-run unless `commit:true`; requires `PARTS_EDIT`)
- Swagger UI at `http://localhost:8080/swagger-ui.html`
