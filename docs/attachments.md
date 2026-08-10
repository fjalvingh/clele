# Part Attachments, Ownership & Datasheets

Part of the Clele documentation — `CLAUDE.md` holds the overview and the index of these files; `API.md` lists the REST endpoints.

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
  - ⚠️ **"Unused" means no part *and* no kit template.** A kit template's images normally have no
    part behind them at all until the kit is generated, so both `deleteOrphans` and the last-link
    check (`deleteIfUnused`) look at `part_kit_template_attachment` too — sweeping on part links
    alone deletes exactly the pictures a kit was set up to hand out. Deleting a kit template runs
    the sweep for the same reason.
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

## Datasheet Preflight & Backfill

CLI tool that downloads the PDFs `part.datasheet_url` points at into `part_attachment`, and reports
whether their specs are actually machine-readable — the groundwork for extracting spec values from
datasheets (the licensing-clean path; see *Part metadata sources* in `docs/ai.md`).

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
  can only be replaced — see *Re-sourcing dead URLs* in this file.
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
