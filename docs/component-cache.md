# Component Cache

Part of the Clele documentation — `CLAUDE.md` holds the overview and the index of these files; `API.md` lists the REST endpoints.

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
