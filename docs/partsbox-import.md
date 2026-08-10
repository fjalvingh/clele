# Partsbox Import

Part of the Clele documentation — `CLAUDE.md` holds the overview and the index of these files; `API.md` lists the REST endpoints.

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
