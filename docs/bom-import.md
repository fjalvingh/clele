# BOM Import & Matching

Part of the Clele documentation — `CLAUDE.md` holds the overview and the index of these files; `API.md` lists the REST endpoints.

## BOM Import & Matching

Upload an EDA tool's BOM export into a project, keep it in the database, and match its lines to
catalogue parts at leisure. Replaces adding sixty parts one search at a time on Project Detail.

**"BOM" means this uploaded file and nothing else.** The project's own list of parts is the *project
parts list* (`project_part`, see `docs/projects.md`); the two used to share the name and it was
never clear which one a screen meant.

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

- **The imported BOM is separate from the project parts list.** `ProjectBomService.apply` (active
  projects only, `requireActiveProject` like every other write to a project) pushes the matched
  lines into `project_part` **and allocates each one out of stock**, exactly as adding the part by
  hand does — it goes through `ProjectService.syncAllocation`, so there is one definition of what
  putting a part on the list means. Lines the shelf cannot cover are allocated short and counted as
  `shortParts` in the result rather than failing the apply. Several lines can resolve to the same
  part (two 100nF caps in different footprints), so quantities are **summed**: `project_part` is
  unique per (project, part). PROVIDED/EXCLUDED/UNMATCHED lines are skipped, and existing
  `project_part` rows no line accounts for are **reported, never deleted** — the imported BOM is not
  the only way parts get into a project. Keeping this an explicit step matters: matching is
  exploratory and half-finished for most of its life, while the parts list is what the build runs
  on, and applying it moves real stock.
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
  colour sits on top of the row in dark mode and washes the text out. The apply button says
  **Apply to project parts list**, never "apply to project BOM".
