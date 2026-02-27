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
- Schema managed by Flyway migrations (V1–V4) in `backend/src/main/resources/db/migration/`
- `ddl-auto: validate` — every schema change requires a new Flyway migration
- Hibernate 6 + PostgreSQL: use plain `byte[]` with `columnDefinition = "bytea"` — do NOT use `@Lob` (maps to OID, which is wrong)

## Key Patterns & Gotchas

- **Axios client** (`api/client.ts`) sets default `Content-Type: application/json` and converts all errors to `new Error(message)` via interceptor. In catch blocks use `(err as Error).message`, never `err.response.status`. For multipart uploads, explicitly override the Content-Type header.
- **TypeScript** `verbatimModuleSyntax` is enabled — use `import { type Foo }` for type-only imports
- **Global exception handler** returns errors under key `"error"` (not `"message"`)
- **Flyway**: `flyway-core` alone handles PostgreSQL in Flyway 9.x — do not add `flyway-database-postgresql` (not managed by Spring Boot 3.2 BOM)
- **Multipart upload limit**: 10MB configured in `application.yml`
- **Image proxy** (`/api/image-proxy?url=`): proxies external images through the backend with browser-like headers. Accepts any HTTP(S) host. Used by Quick Add to avoid CORS and Cloudflare bot-protection issues.

## AI Integration

- Provider: Anthropic Claude (model configured in `application.yml`, default `claude-haiku-4-5-20251001`)
- `AiPartSearchService` calls the Anthropic Messages API via RestTemplate (no SDK dependency)
- `DuckDuckGoImageService` searches for part images via DuckDuckGo
- AI responses may be wrapped in ```json markdown fences — the service strips these defensively

## Key Features

- **CRUD** for parts, categories (hierarchical), locations, stock entries
- **Dashboard** with low stock alerts
- **Quick Add wizard** (3-step): AI part search → select result → confirm details + stock entry
  - Image picker fetches suggestions via DuckDuckGo, displays through backend proxy, uploads selected images as multipart blobs
  - Shows error feedback if image uploads fail (with link to navigate to saved part)
- **Part images**: upload/delete photos per part (max 5), stored as PNG BYTEA in DB
- **Spec definitions**: configurable specification fields (text, number, boolean, select) with units; can be associated with categories
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
