# Clele — Electronic Parts Stock Management

Full-stack web app for managing electronic component inventory with AI-powered part lookup.

## Build & Run

- **Backend**: `mvn21 spring-boot:run` from `backend/` — uses Java 21 toolchain (`mvn21`, not `mvn`)
- **Frontend dev**: `npm install && npm run dev` from `frontend/`
- **Frontend build check**: `npm run build` from `frontend/` (must be in that directory, not project root)
- Backend runs on port 8080; frontend Vite dev server on port 5173 (proxies `/api` to 8080)

## Tech Stack

- **Backend**: Spring Boot 3.2.3, Java 21, PostgreSQL, Flyway, Spring Data JPA, Springdoc OpenAPI, Lombok
- **Frontend**: React 19 + TypeScript, Vite 7, React Router 7, Tailwind CSS 4, Axios 1.x

## Project Structure

```
backend/src/main/java/com/clele/parts/
  config/         RestTemplateConfig (5s connect/10s read timeouts), CorsConfig (/api/**)
  controller/     REST controllers — all endpoints use explicit /api/... prefix
  dto/            Request/response DTOs
  model/          JPA entities: Part, Category, Location, StockEntry, PartImage, SpecDefinition
  repository/     Spring Data JPA repositories
  service/        Business logic

frontend/src/
  api/            Axios client (client.ts), API functions (index.ts), TypeScript types (types.ts)
  components/     Reusable UI: Layout, DataTable, Modal, FormField, Badge
  pages/          Route pages: Dashboard, Parts, PartDetail, Categories, Locations,
                  LowStock, QuickAdd, SpecDefinitions
```

## Database

- PostgreSQL: database `partsdb`, user `partsuser`, password `partspass`
- Schema managed by Flyway migrations (V1–V5) in `backend/src/main/resources/db/migration/`
  - V5 added `part.footprint/mpn/octopart_id` columns and the `stock_movement` ledger table
- `ddl-auto: validate` — every schema change requires a new Flyway migration
- Hibernate 6 + PostgreSQL: use plain `byte[]` with `columnDefinition = "bytea"` — do NOT use `@Lob` (maps to OID, which is wrong)

## Key Patterns & Gotchas

- **Axios client** (`api/client.ts`) sets default `Content-Type: application/json` and converts all errors to `new Error(message)` via interceptor. In catch blocks use `(err as Error).message`, never `err.response.status`. For multipart uploads, explicitly override the Content-Type header.
- **TypeScript** `verbatimModuleSyntax` is enabled — use `import { type Foo }` for type-only imports
- **Global exception handler** returns errors under key `"error"` (not `"message"`)
- **Flyway**: `flyway-core` alone handles PostgreSQL in Flyway 9.x — do not add `flyway-database-postgresql` (not managed by Spring Boot 3.2 BOM)
- **Multipart upload limit**: 10MB configured in `application.yml`
- **Image proxy** (`/api/image-proxy?url=`): proxies external images through the backend with browser-like headers. Accepts any HTTP(S) host. Used by Quick Add to avoid CORS and Cloudflare bot-protection issues.

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
- The AI system prompt is built dynamically from `spec_definition` table — spec names, types, units, and SELECT options are included so the AI returns specs using exact database field names for automatic pre-fill
- AI response parser handles: clean JSON, markdown-fenced JSON, and prose text preceding JSON (extracts from first `[` or ` ``` ` fence)

## Key Features

- **CRUD** for parts, categories (hierarchical), locations, stock entries
- **Dashboard** with low stock alerts
- **Quick Add wizard** (3-step): AI part search → select result → confirm details + stock entry
  - AI returns specs using exact spec definition names → auto-fills spec fields in the confirm step
  - Image picker fetches suggestions via DuckDuckGo, displays through backend proxy, uploads selected images as multipart blobs (client-side fetch + multipart upload to avoid Cloudflare/CORS issues)
  - Shows error feedback if image uploads fail (with link to navigate to saved part)
  - Location field defaults to last used location (persisted in `localStorage` key `quickadd.lastLocationId`)
- **Part images**: upload/delete photos per part (max 5), stored as PNG BYTEA in DB
- **Spec definitions**: configurable specification fields (text, number, boolean, select) with units; can be associated with categories
  - Pre-populated with specs for transistors, diodes, capacitors, resistors, and ICs (inserted directly in DB, not via migration)
- **Part detail page**: image gallery on left, details on right; thumbnail strip; stock entries with unit price; total stock value

## API Endpoints (all under /api)

- `GET/POST /parts`, `GET/PUT/DELETE /parts/{id}`
- `POST /parts/quick-add` — atomic create part + stock entry
- `GET/POST/DELETE /parts/{id}/images`, `POST /parts/{id}/images/from-url`
- `GET/POST /categories`, `GET/PUT/DELETE /categories/{id}`, `GET /categories/tree`
- `GET/POST /locations`, `GET/PUT/DELETE /locations/{id}`
- `GET/POST /stock-entries`, `GET/PUT/DELETE /stock-entries/{id}`
- `GET /dashboard`
- `GET /parts-search?q=` — AI part search
- `GET /parts-search/images?q=` — image suggestions
- `GET /image-proxy?url=` — external image proxy
- `GET/POST /spec-definitions`, `PUT/DELETE /spec-definitions/{id}`
- Swagger UI at `http://localhost:8080/swagger-ui.html`
