# Clele — Electronic Parts Stock Management

Full-stack web app for managing electronic component inventory with AI-powered part lookup.

## Where the detail lives

This file is the overview: the rules that apply everywhere, how to build and run, and the
gotchas that bite in any file. Everything feature-specific lives beside it and is read **on
demand** — open the file when you touch that area. **New design notes go in the matching `docs/`
file (or a new one listed here), not in this one** — that is what keeps it small enough to be
loaded every session.

| file | what is in it — open it when… |
|---|---|
| `API.md` | every REST endpoint: path, params, required permission, response shape. Adding or changing an endpoint |
| `docs/auth.md` | session auth, permissions and how they are recomputed per request, organisations (multi-tenancy), invitations, outgoing mail providers |
| `docs/stock.md` | the movement ledger and the on-hand aggregate, the add/take/move verbs, thresholds, and the location tree |
| `docs/specs.md` | typed spec values (`part_spec_value`), parametric search, RKM/metric-prefix parsing, spec groups and aliases, spec coverage |
| `docs/attachments.md` | the shared `part_attachment` table, part ownership, and the datasheet download/preflight CLI |
| `docs/component-cache.md` | the read-only `cc_*` distributor snapshot consulted before any web lookup (schema in `CCSTRUCTURE.md`) |
| `docs/ai.md` | the Claude lookup and what it costs, datasheet spec extraction, local Ollama categorization, OctoPart/Nexar, and why distributor APIs are off the table |
| `docs/bom-import.md` | uploading an EDA BOM export into a project and matching its lines to parts |
| `docs/part-kits.md` | kit templates (`${…}` value expansion), generating parts in bulk, and undoing a generation |
| `docs/partsbox-import.md` | the one-off Partsbox WebSocket-capture importer |
| `docs/mcp.md` | the read-only MCP endpoint an AI assistant reads the catalogue through — its tools, its API keys, and the OAuth flow (this app is its own authorization server) that lets Claude Desktop connect with a URL alone |
| `docs/printing.md` | label printing — browser path, the Go daemon, Brother QL raster geometry, barcodes |
| `docs/features.md` | the feature list, as a map of what exists |

Working notes kept outside this set: `CCSTRUCTURE.md` (component-cache schema),
`SPECS-REWRITE.md` (the typed-spec migration plan), `SPECS.md` and `CONSIDERATIONS.md`
(untracked analysis).

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
  Needs a `go` toolchain on PATH; skip with `-DskipDaemon=true` (see `docs/printing.md`).

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
  internal/apiclient/       talks to /api/daemon/** (long-poll, media/geometry + version reporting)
  internal/ipp/             minimal IPP client — Get-Printer-Attributes, Print-Job, CUPS-Get-Printers
  internal/printer/         the Driver seam: printer Target, Report, Capabilities
  internal/qlraster/        Brother QL raster protocol + measured print geometry (network, TCP 9100)
  internal/cupsprint/       Dymo LabelWriter via the local CUPS queue (USB, IPP + CUPS raster)
  install.sh, clele-print-daemon.service, README.md
```

## Database

- PostgreSQL: database `partsdb`, user `partsuser`, password `partspass`
- Schema managed by Flyway migrations (V1–V10) in `backend/src/main/resources/db/migration/`
- Last version is V58
- `ddl-auto: validate` — every schema change requires a new Flyway migration. The next free version
  is **V59** (always check `db/migration/` for the real high-water mark before adding one)
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

## API Endpoints

The full list of REST endpoints — paths, parameters, required permissions and response shapes —
lives in **`API.md`**. Keep it up to date when adding or changing an endpoint; the reasoning
behind each area stays here.

Swagger UI: `http://localhost:8080/swagger-ui.html`
