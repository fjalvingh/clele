# Feature Overview

Part of the Clele documentation — `CLAUDE.md` holds the overview and the index of these files; `API.md` lists the REST endpoints.

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
    cache** (585k parts in `cc_*`, free and offline — see `docs/component-cache.md`), then the AI web
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
- **Spec definitions**: configurable specification fields (text, number, boolean, select) with units
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
- **Projects**: a build with a parts list. A project is *active* or *cancelled*, and the difference
  is physical — an active project holds its parts, taken out of stock the moment they went on the
  list. Cancelling gives every one of them back to the location it came from and keeps what the
  project needed; reactivating fetches it all out again, showing which lines the shelf can no longer
  cover. Individual parts can be returned as you go, and a cancelled project can be deleted (see
  Projects above)
- **BOM import**: upload an EDA tool's CSV export into a project, then match its lines to catalogue
  parts a few at a time over as long as it takes — the file and every decision are stored, exact
  hits match themselves, and re-uploading a revised export merges rather than starting over.
  Applying the matched lines writes them onto the project parts list and allocates them from stock
  (see BOM Import & Matching above)
- **Part kits**: define a pack bought as a set — a resistor kit, a capacitor assortment — as one
  part template plus the list of values it varies over (including the photos every part gets), then
  generate every part with its stock in one action — and take that run back again while nothing it
  made has been touched (see `docs/part-kits.md`)
- **Component cache**: a local snapshot of a distributor catalogue, consulted when adding a part
  before any web/AI lookup and available on Part Detail as "Look up in cache" — free, offline and
  instant (see `docs/component-cache.md`)
- **Label printing**: per-user choice of the browser print dialog or silent printing via an
  installed network daemon, configured on My Account (see `docs/printing.md`)
