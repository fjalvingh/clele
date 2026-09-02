# Specifications — typed values, groups, coverage

Part of the Clele documentation — `CLAUDE.md` holds the overview and the index of these files; `API.md` lists the REST endpoints.

## Typed Spec Values

`part.specs` **was** a loose JSONB map, which could not answer the query a parts database exists
for: "Vds ≥ 60 V", "resistance = 4.7 kΩ". Measured on the catalogue before the change — ~1,500 of its strings are Partsbox
ranges (`4.5..null`) that convert-to-number has to *refuse*, ~400 are unit-bearing strings where
`100nF`, `0.1uF` and `1e-7` are three unrelated values, and 11,384 are bare numbers invisible even to
the full-text index. **`part_spec_value` (V50) replaces it with typed rows.** Design note and the
full migration plan: `SPECS-REWRITE.md`.

**Complete.** `part.specs` is gone; the typed rows are the storage.
`PartDTO.specs`, the sparse-specs count and the Parts free-text search all come from
`part_spec_value`, and **`PartSpecValueService.sync(part, specs)` is the only way a spec value is
written** — it takes the map as an argument rather than reading it off the entity, which is what let
the column go. A caller that needs the part's current values to merge against reads them back with
`specsOf`.

**The flip was verified equivalent, not assumed.** Against a copy of the real catalogue: the spec key
set is identical for **all 1,102 parts**; the sparse count is identical (335); and over 12 search
terms × 1,102 parts exactly **one** (part, term) pair changed — `"16 mhz"` stopped matching a 2.2 MHz
op-amp whose *supply-voltage range* happened to end at 16. That was an accidental hit from tokenising
`"4..16"`, and dropping it is the same rule V43 already applied to JSON numbers. Of 21,719 values, 34
render differently and every one is intended: whitespace normalised, plain-vs-scientific notation, a
human range (`-20°C to +70°C`) parsed into `-20..70`, or a unit string (`150 ns`) parsed to its base
unit.

- **`PartDTO.specs` is shaped exactly like the JSONB it replaces** (`PartSpecValueService.specsOf`):
  a numeric row yields the bare base-unit number, a range the Partsbox `"min..max"` form, text passes
  through. It deliberately does **not** render — the edit widgets bind to the stored base number and
  would break on `"4k7"`. Rendering stays in `units.ts` on the way to the screen.
- **List paths batch it.** `PartService.specsFor(parts)` loads one map for the whole page;
  `toDTO(Part)` alone runs a query per call, so a search result mapped through it would be a query
  per row.
- ⚠️ **A numeric-looking string becomes a number only when the round trip is lossless**
  (`PartSpecValueService.numericIfLossless`). `"0805"` is an imperial case code in a family-less
  field: read as 805 it lost both its value and its place in the free-text search, so searching
  "0805" stopped finding the part. The comparison is against the value **as it will be read back**
  (trailing zeros stripped), or `"1.50"` would count as lossless and come back as `1.5`.

Measured coverage once V51's families are in place — **12,872 of 21,719 values (59%) become
numerically queryable**: 11,384 bare numbers, plus 1,492 ranges that were entirely dead before (no
query could reach inside `"3..16"`, and convert-to-number has to refuse it). 8,553 correctly stay
text — packages, dates, logic functions, enumerations. Across the whole catalogue exactly **one**
value in a family-bearing field fails to parse (`5V ± 10%`).

⚠️ **A family asserts "a bare number in this field is in the base SI unit", so verify before
assigning one.** That held throughout here — the Partsbox/Octopart import stored base units
(`inputoffsetvoltage_vos_` 2e-5…0.02 V, `propagationdelay` 1.9e-9…1e-5 s) — but it is a property of
the data, not a rule, and checking is what kept three fields out: `datarate` is 1…480 (Mbit/s, so a
bit/s family would be wrong by 10⁶), `weight` is in grams while the SI base is the kilogram, and
`memorysize`/`ramsize`/`density` mix bits, bytes and KB.

- **`PartSpecValueService.sync(part)` is the single write path** and takes the part's
  *already-resolved* map, making the rows match it exactly. It deliberately does not reimplement
  `specsMode` MERGE/REPLACE — `resolveSpecs` has already applied that, so copying its outcome is
  simpler and self-correcting. Being idempotent is what lets one method serve both the intake paths
  and the coming backfill. Wired into `PartService.saveAndSync` (create / update / applyOctopart /
  applyAiLookup), `QuickAddService`, `PartKitTemplateService`, the Partsbox importer, and the two
  bulk paths in `SpecDefinitionService` (merge, convert-to-number) that rewrite `part.specs`.
- ⚠️ **`data_type` decides which columns a value uses, and nothing else does.** A **NUMBER**
  definition's value is read by `NumericSpecParser` into `value_num`/`value_min`/`value_max`;
  **TEXT / SELECT / BOOLEAN** are stored verbatim in `value_text`, never parsed, never split into
  bounds, however numeric they look. That is what keeps `"0805"` an imperial case code and
  `"2K x 8"` a memory organisation. It was not always so: classification used to be driven by the
  *value* plus the unit family, so a TEXT field with a family held parsed numbers while a NUMBER
  field quietly held text — and search then had to guess which half of the row to look in.
- ⚠️ **A NUMBER value that will not parse is dropped, not kept as text.** A number parked in
  `value_text` is invisible to every parametric query — "supply voltage between 3 and 5 V" cannot
  see a part whose supply voltage reads `"5V ± 10%"` — so the field looks populated while answering
  nothing. An empty field is the honest version of that. **The corollary is that a mis-typed
  definition destroys values**: a field that is really text but declared NUMBER loses them the next
  time each part is saved, which is what `SpecResyncService`'s dry run exists to show first.
- **What a NUMBER field accepts** (`NumericSpecParser`, pinned by `NumericSpecParserTest` against
  values that actually occur here): the family's own spellings including RKM (`4k7`, `100nF`); unit
  words folded to symbols (`ohms`, `microseconds`, `degrees`); qualifiers and bracketed notes
  dropped (`5V DC`, `1.4 A RMS`, `220 mA (6V, no-load)`); ranges as `3..16`, `min..nominal..max`,
  `-40 °C ~ 105 °C`, `15 V to 35 V`, `4.8-6.0 V`, `-15–70 °C`; tolerances (`5V ± 10%` → 4.5/5/5.5);
  and one-sided bounds (`> 600 Hz`, `up to 50 W`, `5 V max`). Two rules earn their keep: a **plain
  hyphen** separates only when the value does not open with a sign *and* both halves parse, so
  `-40-125` and `1e-7` stay single values; and a **unit written once** is lent to the bare bound
  beside it, or `500-2500 µs` would read as 500 seconds to 2.5 ms.
- **A field with no family and no declared `unit` accepts only a plain number.** `"16 mA"` there is
  refused rather than read as 16 — nothing says what its numbers are counted in.
  BOOLEAN is text (`"true"`/`"false"`): filtered by equality, never by range.
- **A value may carry a nominal *and* bounds** (V56). min/typ/max is how a datasheet states a
  parameter — "4.5 V, 5 V typical, 5.5 V" is one fact — so `value_num` and `value_min`/`value_max`
  are no longer mutually exclusive; V50's one-shape check now only keeps *text* apart from the
  numbers. The wire form is `"min..nominal..max"` (`PartSpecValueService.valueOf`), any component
  written `"null"` when open; the bare number and the two-part `"min..max"` are untouched, so
  everything stored before V56 reads back byte for byte. **Search needed no change**: a criterion
  asks whether the row has *some* value satisfying it, so the nominal and the interval are simply
  two chances to match. There is no "range or scalar" distinction anywhere any more: empty bounds
  *are* a scalar.
- **Entering one.** The numeric spec editor is `components/SpecNumberField` — one box, plus a toggle
  beside the label that opens three (min / nominal / max). A value that already has a bound opens
  them by itself, since there is no other way to show it; collapsing drops the bounds, explicitly,
  rather than keeping them stored and invisible. One prefix dropdown serves all three boxes: a band
  whose bounds sit in different decades is not a band anyone writes. `utils/specs`
  (`splitSpecNumber` / `joinSpecNumber`) is the frontend mirror of the wire form, and
  `PartDetail.formatStoredNumber` renders it — "5 V (4.5 V ~ 5.5 V)", "≤ 16 V".

- **An unknown key auto-creates a definition** at write time, since a row needs a
  `spec_definition_id` (the JSONB could hold a loose key indefinitely). The type is inferred from
  the one value in hand — weaker than `rescanFromParts`, which sees every value of a key at once and
  can spot a SELECT, which is why a rescan still earns its place. **No unit family is ever guessed.**
  This inference now carries more weight than it used to: it decides the *type*, and the type decides
  whether that key's values are ever read as numbers — which is where the "`0805` is a code, not the
  number 805" losslessness rule (`numericIfLossless`) still earns its keep.
- **Re-classifying what is already stored**: a value is classified once, when written, so a
  definition that later gains a family or has its type corrected does not reach its own values.
  `SpecResyncService` reads every part's values back out and writes them again through the one write
  path. It is a **dry run by default**, and prints the values a commit would drop, per spec field:
  `mvn21 spring-boot:run -Dspring-boot.run.profiles=specs -DskipFrontend=true`, then
  `-Dspring-boot.run.arguments=--specs.dry-run=false` to commit. The preview and the commit are the
  same code path (`PartSpecValueService.preview` / `sync`), so the list cannot describe a different
  outcome than the one that follows.
- **`spec_definition.unit_family`** (a `UnitFamily` code, mirroring the `CcUnits` families) is what
  licenses parsing at all. **Null means never parse** — the safe default, and deliberately not a gap
  to fill in for tidiness. Note the name is not the family: `naturalthermalresistance` is °C/W not Ω,
  `inductancetolerance` is a percentage, `numberofresistors` is a count.

### Giving the measured TEXT fields a family

`scripts/spec-text-fields-to-numeric.sql` is the repeatable pass over an installation's own
definitions: it turns the family-bearing TEXT fields into NUMBER (their values were already being
parsed — only the widget was a text box) and assigns a family to the fields whose name *and stored
values* agree on what they measure. Run on the development catalogue it moved 137 definitions.
The judgement calls are in the script's header, along with what it deliberately leaves alone —
torque, weight, RPM, memory sizes, multi-dimension strings — and its closing query lists the TEXT
fields whose names still read like measurements, for the next pass. It does **not** reclassify the
values already in `value_text`: only `sync` parses, so a value catches up when its part is saved
again, or through `POST /spec-definitions/{id}/convert-to-number`.

### Parametric spec search

The query the whole rewrite exists for. `GET /parts?spec=<jsonName>:<op>:<value>`, **repeated** —
each criterion runs as its own indexed query and the results are intersected, so they AND together
like every other filter. Ops: `eq` `gte` `gt` `lte` `lt` `contains` `any`. The UI is a
**Specifications** block in the Parts screen's "More search options" panel (field → operator →
value), mirrored in the URL so a search is bookmarkable.

- **The value is written the way people write it** and parsed server-side against the spec's own
  unit family, so `100nF`, `0.1uF`, `1e-7` and `100n` are one search. A value that will not parse as
  a number falls back to a text match, which is how `dielectric:eq:X7R` works through the same path.
- **Interval semantics**: a criterion asks whether the part *has some value satisfying it*, so a
  range answers on the bound that could — `supplyvoltage:eq:3.3` matches a part specified `2..5.5`
  (170 of them in the development catalogue), and `gte 60` matches `4..70`. An open bound is
  unbounded and satisfies any comparison in its direction.
- ⚠️ **An unknown spec name matches nothing rather than being ignored.** Silently dropping an
  unrecognised filter shows the user a longer list and lets them believe it was filtered.
- ⚠️ **Numeric values are rounded to 12 significant digits on write**
  (`PartSpecValueService.storedScale`) — **this is what makes equality work at all.** The rewrite
  assumed NUMERIC made the component cache's `value_exact`/`value_num` split unnecessary because
  only cc's numbers were JSON doubles. Ours are too: the catalogue's 100 nF capacitor arrives from
  the JSONB as `1.0000000000000001e-7`, so "capacitance = 100 nF" found nothing. NUMERIC preserves
  what it is given; it cannot un-ruin a number that was a double first. Twelve digits is far beyond
  any component tolerance and is the precision `units.ts` has always displayed at.
- **A criterion is routed by `data_type` too** (`PartService.matchingPartIds`): a NUMBER field is
  searched numerically and only numerically — it has no text rows to fall back to, and a term that
  is not a number matches nothing rather than quietly widening into a substring search. Text fields
  answer `eq` and `contains`; an ordering comparison on one means nothing and returns nothing.
- **Which fields a part gets is decided per part, not per category.** Both the create (Parts) and
  edit (`PartEditModal`) dialogs render `components/PartSpecEditor`: only the specs the part
  actually carries, grouped by spec group, each removable, plus a type-ahead over every definition
  in the organisation to add one. There is no category → spec field mapping any more — the
  `category_spec` table, `Category.specs`, `CategoryDTO.specIds`, `getInheritedSpecs` and
  `GET /spec-definitions/for-category/{id}` were dropped in **V60**. A category-scoped list was
  never the right pre-fill (a part carries what its datasheet says, not what its category lists)
  and with ~1000 definitions the no-category fallback showed all of them at once.

### Upgrading an installation

The rows are filled by **V53 itself**, so an upgrade from any older version just works. The
`specvalues` backfill CLI that carried the data during development is gone with the column it read.

⚠️ **V53's SQL fallback is deliberately less capable than the Java classifier was**, and the
difference is documented in the migration: it cannot parse `"150 ns"` against a unit family, does
not know RKM, and does not recognise the `A ~ B` / `A to B` range spellings. Measured over the
development catalogue that is **15 values out of 21,719** — they land as text rather than as typed
numbers, which is visible and correctable, not lost.

### RKM code (`4k7`, `100R`, `2n2`) and the prefix window

The decimal point is the least reliable character in electronics, so IEC 60062 puts the multiplier
letter in its place. `MetricUnitParser` accepts it (infix `4k7`/`4R7`/`2n2`, trailing `47k`/`100n`)
and `MetricUnitFormatter` produces it for resistance, capacitance and inductance;
everything else keeps `9 mA`. Together they are an exact inverse pair, which is why **no rendering is
ever stored** — the component cache stores a `display` because it is a read-only snapshot of someone
else's parse, whereas here we do the parsing.

- ⚠️ **Each family has a prefix window, and it binds the renderer as well as the parser.** Resistance
  refuses a bare `m µ n p`, capacitance and inductance a bare `k M G T` — `4m7` and `4M7` differ by
  nine orders of magnitude and one shift key with no unit symbol to make the mistake visible. It has
  to bind rendering too: `draintosourceresistance` really holds `0.0087`, and an unrestricted
  engineering renderer would print `8m7`, which the parser is then required to refuse. Outside the
  window the decimal point stays and the marker suffixes: `0.0087R`, which parses back exactly.
- ⚠️ **The window binds the *bare letter* only** — `"15 mΩ"` parses normally. Where the symbol is
  written out the reader and the parser see the same thing, and both the component cache and the
  datasheet extractor emit that form for the sub-ohm values (RDS(on), ESR, DCR) that are ordinary
  here. Milliohm *resistors* are rare; milliohm *resistance values* are not.
- ⚠️ **`R` is a resistance marker only.** On an SMD inductor `4R7` conventionally means 4.7 **µH**,
  with the µ implied by the component class rather than written — reading that implication is the
  same class of error as taking 4 KB for 4000, so `R` in an inductance field does not parse.
- ⚠️ **Case is load-bearing**; `PREFIX_EXP` is case-sensitive with `K` as a kilo alias (also the usual
  RKM spelling). Do not "clean this up" into a case-insensitive match.
- ⚠️ **Every value goes through `MetricUnitParser.normalizeSpaces` — `trim()` and `strip()` are both
  insufficient.** `trim` only removes characters at or below U+0020, and `strip` defers to
  `Character.isWhitespace`, which deliberately answers **false** for the non-breaking spaces U+00A0
  and U+202F. Real vendor text uses all of them *between the number and its unit*, which is exactly
  where it breaks parsing: the catalogue's own `"5.5 V"` uses a **thin space** (U+2009), so the tail
  read `" V"`, matched no unit, and the value silently stayed text. It was one value out of 21,719
  and was found only by measuring the backfill against real data. `units.ts` mirrors the same
  normalisation (JS `trim()` handles the ends but not the middle either).
- **Three files must stay in step**: `MetricUnitParser`, `MetricUnitFormatter` and
  `frontend/src/utils/units.ts` (`UNIT_FAMILIES`, `formatFamilyValue`, `parseFamilyValue`).
  `MetricUnitParserTest` pins the example table on the Java side; the frontend has no test runner, so
  the TS side was verified by running the same table through it.

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

### Identifying a part from an uploaded datasheet

The mirror image of the section above, and Quick Add's last fallback: **upload the PDF** and let it
say what the part is (`POST /parts-search/from-datasheet`, `DatasheetSpecExtractionService.identify`).
`extract` is told the part and wants its values; `identify` has only the document and must name the
part as well — so it returns the same `PartSearchResultDTO` a web search does (plus `details`), the
result lands on the same confirm step, and the same cards render it.

Everything after the identification is the shared machinery: the same `buildExcerpt`, the same
`DatasheetAnalyzer` routing (a `NO_TEXT_LAYER` scan is refused before any spend), the same
`canonicalizeKeys`. The prompt is assembled from four blocks — `READER_INTRO`, one of
`EXTRACT_FIELDS`/`IDENTIFY_FIELDS`, `SPEC_KEY_RULES` and `READING_RULES` — and only the second
differs. The spec-key block is shared for the reason `SpecFieldCatalog` exists: a key described one
way in one prompt and another way in the other lands in `part.specs` as two fields.

**One part number, never a list.** A family datasheet ("xx555 Precision Timers", covering NA555 /
NE555 / SA555 / SE555) first came back with `mpn` as all four joined by commas — which would create
a part called that. The prompt now demands exactly one, taking the member the **uploaded filename**
names and falling back to the first of the title block; `ne555.pdf` then yields `NE555`. Measured on
that datasheet: 8.2k input / 360 output ≈ **1¢**, no web searches.

A document that names no part number at all is a **422**, not an empty result — the user chose the
file believing it was a datasheet and needs to be told it did not read as one.

**The file is attached after the part exists, and is never stored before that.** The browser keeps
the `File` from the lookup and uploads it as a `DATASHEET` once `quickAdd` returns (the same order,
and for the same reason, as the `datasheetUrl` download below). A PDF whose confirm step is
abandoned therefore leaves nothing behind, and a part created this way carries the exact document
its values were read from. An upload over the 10 MB multipart limit is now a **413** with a readable
message rather than a bare 500 (`GlobalExceptionHandler.handleTooLarge`).

`DatasheetIdentificationTest` pins the mapping (`{key,value,page}` objects out as `"key: value"`
strings — a mismatch there is a part saved with no specs, not an error) and both refusals.

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
