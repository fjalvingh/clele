# Spec storage rewrite — typed `part_spec_value` rows replacing the loose JSONB

Working note, 2026-08-08. Design agreed in conversation.

**Status: steps 1–2 of the migration plan are built** (2026-08-08) — V50 + the RKM parser/formatter
pair + the dual-write funnel, then V51's unit families. The JSONB is still authoritative for reads;
nothing user-visible has changed. Steps 3–6 (backfill, flip reads, parametric search UI, drop
`part.specs`) are still open. What landed:

| | |
|---|---|
| `model/UnitFamily` | the family vocabulary — base unit, prefix window, RKM style, scale-free flag |
| `service/MetricUnitParser` | RKM forms + family window, on top of the existing base-unit parsing |
| `service/MetricUnitFormatter` | the inverse; why nothing stores a rendering |
| `utils/units.ts` | the frontend mirror (`UNIT_FAMILIES`, `formatFamilyValue`, `parseFamilyValue`) |
| `V50__part_spec_value.sql` | the table, its CHECK, three partial indexes, `spec_definition.unit_family` |
| `model/PartSpecValue` + repository | composite-key entity; the shape mutators are the only way in |
| `service/PartSpecValueService` | `sync(part)` — the single write path, idempotent |
| wiring | `PartService.saveAndSync`, QuickAdd, kit generation, Partsbox import, spec merge + convert-to-number |

| `V51__spec_unit_families.sql` | families for 189/209 NUMBER + 18/43 TEXT definitions, by hand |

Verified end to end against a scratch database built from V1→V51: every classification branch lands
in the right shape, MERGE leaves untouched keys alone, REPLACE removes their rows, and the row count
matches the JSONB key count. 126 backend tests green.

**Measured after step 2** (development catalogue, 21,719 values): **12,872 (59%) become numerically
queryable** — 11,384 bare numbers plus **1,492 ranges that were entirely dead**. 8,553 correctly stay
text. Exactly **one** value in a family-bearing field fails to parse (`5V ± 10%`), so the
"unparseable residue" the plan expected step 3 to surface is, in practice, empty.

Three things learned in step 2 that were not in the original design:

- **The ranges live in TEXT definitions, not NUMBER ones** — all 1,488 of them, in
  `operatingtemperature` (744), `supplyvoltage` (476), `powerdissipation` (76) and friends. They are
  TEXT precisely *because* no number column could hold them. Giving a TEXT definition a family is
  therefore the single highest-value part of step 2, and it works because the family drives storage
  while `data_type` only drives the edit widget. Their `data_type` stays TEXT: a range is not a number.
- **Two more range spellings were worth supporting.** `A ~ B` is the component cache's own `display`
  format and `A to B` is how a datasheet writes it, so both keep arriving from live sources rather
  than only sitting in the backlog. A hyphen is deliberately not a separator.
- **V42's cross-type near-duplicates can now be merged.** `operatingsupplyvoltage` (NUMBER, scalars)
  and `supplyvoltage` (TEXT, ranges) were left unmerged because one held a scalar and the other a
  range and no single column could hold both. `part_spec_value` holds either per row, so the reason
  is gone — the same applies to `powerconsumption`/`powerdissipation`,
  `reversevoltage_dc_`/`reversevoltage` and `breakdownvoltage`/`reversebreakdownvoltage`. Worth doing
  once reads are flipped, not before.

## The problem

`part.specs` is a loose JSONB map (`jsonName → whatever the source sent`). Measured on the
current catalogue (1,102 parts, 863 with specs, ~21,700 stored values):

- **10,335 values are strings**, and among those:
  - **~1,500 are Partsbox ranges** (`4.5..null`, `3..16`) — dead as numbers; the
    convert-to-number tool has to *refuse* them today.
  - **~400 are "number + prefix + unit" strings** (`150 ns`, `16K`, `100nF`). A search for
    "capacitance ≥ 100 nF" is impossible, and even equality fails: `100nF`, `0.1uF` and
    `1e-7` are three different strings for one value.
- **11,384 values are bare JSON numbers**, whose unit is only knowable through the matching
  `spec_definition` — and only 96 of 627 NUMBER definitions declare one, so most stored
  numbers have no defined magnitude at all.

Search over specs today is V43's full-text index over the **string** values only (finds
"X7R", "SOT-23") plus the sparse-specs count. There is no way to express "Vds ≥ 60 V" or
"resistance = 4.7 kΩ" — the core parametric query a parts database exists for. Bare numeric
values are invisible even to the FTS.

## What the `cc_*` model teaches

The component cache (585k parts, 8.6M attribute values — see `CCSTRUCTURE.md`) stores the
same kind of data and gets the fundamentals right. **Adopt:**

1. **Numbers normalized to SI base units at write time.** 100 nF is `1e-7`, full stop.
   Prefixes become a display concern; comparison and search just work.
2. **Typed value columns** — numeric for `<`/`>`/`BETWEEN`, text for the rest.
3. **One row per (part, attribute)** with a composite PK, so per-attribute filters are
   simple indexed `EXISTS` clauses.
4. **Unit families on the definition** licensing conversion — already built here as
   `CcUnits` for the cache translation.

**Reject (overkill or wrong at our scale):**

- **Value interning** (`cc_attribute_value` rows shared by ~35 parts each) — compression
  for 8.6M links. At ~22k values it buys nothing and costs indirection everywhere.
- **The `value_exact` / `value_num` split** — exists because cc's source numbers are JSON
  doubles (`1e-7` stored as `1.0000000000000001e-07`, so `=` silently fails). Storing
  `NUMERIC` instead of `double precision` lets one column serve both `=` and ranges.
- **Slots/format templates** for multi-value conditions (`15mΩ @ 10V, 4.5A`) — real but
  rare in our data; such a value simply stays text.
- **A stored `display` rendering.** cc needs it because that database is a read-only
  snapshot of someone *else's* parse — their rendering is ground truth and the conversion
  is the suspect party. In our own editable table the trust runs the other way (**we**
  parse at write time), so a stored rendering is pure denormalization: nothing keeps it in
  step when `value_num`/`min`/`max` are edited. Display is **derived, never stored** — see
  below.

## The design

### Schema (V50)

```sql
CREATE TABLE part_spec_value (
    part_id            BIGINT  NOT NULL REFERENCES part(id) ON DELETE CASCADE,
    spec_definition_id BIGINT  NOT NULL REFERENCES spec_definition(id) ON DELETE CASCADE,
    value_num          NUMERIC,           -- scalar, in the definition's base unit
    value_min          NUMERIC,           -- range bounds, base unit (either NULL = open)
    value_max          NUMERIC,
    value_text         TEXT,              -- TEXT/SELECT/BOOLEAN values + anything unparsed
    PRIMARY KEY (part_id, spec_definition_id),
    CHECK (num_nonnulls(value_num, value_text)
           + (value_min IS NOT NULL OR value_max IS NOT NULL)::int = 1)
);
CREATE INDEX idx_psv_def_num  ON part_spec_value (spec_definition_id, value_num)
    WHERE value_num IS NOT NULL;
CREATE INDEX idx_psv_def_text ON part_spec_value (spec_definition_id, value_text)
    WHERE value_text IS NOT NULL;
```

`spec_definition` gains **`unit_family`** (nullable text, values = the `CcUnits` families).
The family is what licenses parsing "150 ns" → `1.5e-7` in base `s`. It complements the
existing `unit` + `metric_prefix`; a definition without a family never gets its values
parsed to numbers.

No `organisation_id` — the row reaches its tenant through `part_id`, like every other
per-part table.

### The core rule: parsed or raw, never both

The CHECK enforces exactly one shape per row:

- **Parsed scalar** → `value_num` only. The incoming string parsed cleanly against the
  definition's unit family (`MetricUnitParser`, already the convert-to-number engine).
- **Parsed range** → `value_min`/`value_max` (either bound may be open). `4.5..null`
  becomes `min = 4.5`; "supply voltage covers 3.3 V" becomes
  `value_min <= 3.3 AND value_max >= 3.3`. The ~1,500 currently-dead range strings become
  first-class queryable values.
- **Raw** → `value_text` only. TEXT/SELECT/BOOLEAN definitions, plus any value that did
  not parse. Nothing was extracted from it, so nothing can drift.

**Display is derived on demand**: render `value_num` (or `min ~ max`) with the metric
prefix and unit — the exact inverse of the parse. `MetricUnitParser` (Java) and `units.ts`
(frontend) are already that inverse pair and already carry the keep-in-sync rule. Rendering
cannot drift from the value because it is computed from it every time. Cosmetic formatting
is lost (`4.7k` and `4700` both come back in the family's canonical form) — acceptable, and
for resistance/capacitance/inductance the canonical form is the one people actually write
(see RKM below).

The "never wrong about magnitude" concern that motivated cc's stored `display` is covered
differently here: when a parse would be *uncertain* (no unit family, or an ambiguous family
like `data_size` where 4 KB may mean 4096), we do not parse at all and the value stays
text. Only deterministically-converted values are ever rendered from numbers, and a
round-trip render of those is semantically identical to the input. Same rule
`ComponentCacheService` already enforces on the cache boundary.

**Edit flow**: the form shows the rendered value; the user types a string; the backend
re-parses it against the definition. One parser, one renderer, no stored intermediate.

### RKM code — `1K2`, `4R7`, `2n2`

The decimal point is the least reliable character in electronics: it vanishes on a
silkscreen, a photocopy, a low-resolution scan and a badly kerned datasheet table. IEC 60062
("RKM code") removes it by putting the multiplier letter *in its place* — `1K2` is 1.2 kΩ,
`4R7` is 4.7 Ω, `2n2` is 2.2 nF, `1u5` is 1.5 µF. This is how components are marked, how
schematics label them, and how people type them. **The parser must accept it and, for the
families where it is the native notation, the renderer must produce it.**

**Grammar.** One optional letter, either infix or trailing:

| form | example | meaning |
|---|---|---|
| `<int><letter><int>` | `1K2`, `4R7`, `2n2`, `4M7` | letter is both multiplier *and* decimal point |
| `<number><letter>` | `100R`, `47K`, `100n` | multiplier only; no fractional part |
| `<number><letter><unit>` | `4.7 kΩ`, `100nF` | what the parser already handles |

More than one fractional digit is fine (`4K75` = 4750 Ω). The letter is the family's SI
prefix symbol, or — for the base-unit position — the family's own unit symbol (`4V7`,
`2F2`, `1H5`), with **`R` accepted as the base marker for resistance only**. `R` is *not*
generalised to other families, and that restriction is the important one: on an SMD inductor
`4R7` conventionally means 4.7 **µH**, with the µ implied by the component class rather than
written. Reading that implication would be exactly the "4 KB becomes 4000" error `CcUnits`
is built to refuse, so an `R` in an inductance field does not parse and the value stays
text.

⚠️ **Case is load-bearing and must stay so.** `4M7` is 4.7 MΩ; `4m7` is 4.7 mΩ. The existing
`PREFIX_EXP` table is already case-sensitive with `K` as a tolerant alias for kilo, which is
exactly right for RKM (`4K7` is the common spelling) — do not "clean this up" into a
case-insensitive match.

#### The prefix window: `m` is refused for resistance, `M` for capacitance

Case-sensitivity is necessary but not sufficient. `4m7` and `4M7` differ by **nine orders of
magnitude** and by one shift key, with no unit symbol present to make the mistake visible —
the reader sees a plausible value either way. So the bare-letter form is restricted to the
prefixes that are *native* to the family, and each family's window is applied to **parsing
and rendering alike**, which is what keeps every rendered value re-readable:

| family | bare-letter prefixes | base marker | refused |
|---|---|---|---|
| resistance | `T G M k` | `R` | `m µ n p` |
| capacitance | `m µ n p` | `F` | `k M G T` |
| inductance | `m µ n p` | `H` | `k M G T` |

A refused letter is a **parse failure**, handled like every other: hand entry gets an error
naming the escape hatch, machine intake leaves the value as text in the backfill residue.
A genuine milliohm value is written in the base unit — `0.0047R`, or plain `0.0047`.

**Rendering respects the same window.** This is not optional tidiness: `draintosourceresistance`
already holds 0.0087, 0.009, 0.03, 0.069 and 0.088 (8.7 mΩ … 88 mΩ), and an engineering-prefix
renderer would print those as `8m7`, `9m`, `30m` — output the parser is now required to
refuse. Below the window the value renders as a decimal in the base unit (`0.0087R`), above it
likewise. Round-trip therefore holds for every value, which the unrestricted renderer would
have broken.

⚠️ **The explicit unit-symbol form is still accepted.** `15 mΩ`, `100 µF`, `4.7 mH` parse
normally — the restriction is on the *bare letter*, where the case is the only information.
Where the symbol is written out the reader and the parser see the same thing, and refusing it
would break machine intake for no gain: the component cache and the datasheet extractor both
emit `15 mΩ`, and milliohm-scale resistance is ordinary in this catalogue even though
milliohm *resistors* are not — RDS(on), ESR, contact resistance and DCR are all routinely
sub-ohm (see the values above). Rendering never *produces* that form for these three
families, but parsing accepts it.

> Flagged for a second opinion: this keeps `15 mΩ` working, which the "milliohm resistors are
> not common" reasoning did not consider — the common milliohm values are MOSFET and capacitor
> parameters, not resistors. Tightening the refusal to cover the explicit form too is a
> one-line change if that is wanted, at the cost of every `mΩ` from an automated source
> landing in the residue.

**Parse widely, render narrowly.** The grammar is unambiguous for any family with a base
unit, so RKM is accepted **everywhere** — `1M2` in a frequency field is 1.2 MHz and there is
nothing to be gained by refusing it. Rendering is the opposite: the note asked for
resistance, capacitance and inductance, and those are the three families where RKM *is* the
normal written form. Everywhere else the ordinary `9 mA` rendering stays. So the render
style is a property of the **family**, not of each definition:

| family | base | renders as |
|---|---|---|
| resistance | Ω | `4k7`, `100R`, `4M7`, `47k` |
| capacitance | F | `2n2`, `100n`, `1u5`, `4p7` |
| inductance | H | `2u2`, `100n`, `4m7` |
| everything else | — | `9 mA`, `1.5 ns`, `3.3 V` |

Render algorithm: pick the engineering prefix (`units.ts` `pick()` already does), then place
the letter — at the decimal point if the mantissa has a fractional part (`4.7k` → `4k7`), at
the end if it does not (`47k` → `47k`, `100` → `100R`). The base-position letter is `R` for
resistance and the unit symbol otherwise. Written with the SI-correct symbol (`4k7`, not
`4K7`); the parser accepts either, so someone who types `4K7` gets the right number stored
and sees `4k7` back.

**Round-trip.** For these three families the render is now the same notation the user typed,
so `4k7` in and `4k7` out — the cosmetic loss noted above disappears where it was most
likely to annoy. Range bounds render the same way on each side (`4k7 ~ 5k1`).

**Nothing in the catalogue needs this yet, and that is fine.** Measured: **zero** of the
~10,300 stored spec strings are RKM-shaped; the only shorthand present is a single
`memorysize: 16K`, which the `data_size` ambiguity keeps as text regardless. The value is in
hand entry and in future sources — and the notation is plainly already in use around the
data, e.g. the part number `MCF0805B2R50FSTR` (a 2.50 A fuse).

**Where it lands in the code.** `MetricUnitParser.parseToBase` gains the infix form ahead of
its existing `<number><prefix><unit>` path (today `4k7` fails cleanly — `matchPrefix` rejects
the tail `k7` — so this is purely additive and cannot change an existing parse), and
`suggestUnit` needs to cope with the letter being infix rather than leading. `units.ts` gains
the matching renderer. That is a **second** parse/render pair straddling the Java/TS boundary,
under the same keep-in-sync rule as the prefix table — worth pinning with a shared table of
examples tested on both sides. This part is independently useful: it improves
convert-to-number's dry run today, before any of the rest of this note is built.

### API compatibility — the map survives as a shape

`PartDTO.specs` stays a `jsonName → value` map, assembled from the rows (numeric values
rendered, text passed through). So the frontend spec sections, the AI prompt building
(`SpecFieldCatalog`), Quick Add pre-fill and `canonicalizeKeys` keep their current shape.
Intake changes in one place: where `PartService.resolveSpecs` writes the JSONB today, it
parses each value against its definition and upserts rows instead. `specsMode`
MERGE/REPLACE semantics carry over unchanged (row upsert/delete instead of map merge; a
key sent null/blank deletes its row).

### Unknown keys

Today an unknown key is kept loose in the JSONB and "Rescan from parts" promotes it later.
A row table needs a `spec_definition_id`, so: **auto-create a TEXT definition** (default
group, `SpecNameHumanizer` title) on first sight at write time. Only **7 keys** in the
whole catalogue currently lack a definition, so the loose-key mechanism is carrying almost
no weight; the existing merge / alias / convert-to-number tooling is the cleanup path.
After the backfill, "Rescan from parts" reduces to a legacy no-op.

### What search becomes

- **Parametric filters** on the Parts page: "spec + operator + value" criteria compile to
  one indexed `EXISTS` per criterion — the exact query pattern `CCSTRUCTURE.md`
  demonstrates, measured fast there at 585k parts, trivial at 1k. Range-typed values
  answer containment queries the JSONB never could.
- **Value search by meaning, not spelling**: "everything with 100 nF" is
  `value_num = 1e-7` regardless of whether the source wrote `100nF`, `0.1 µF` or `1e-7`.
- **Free-text search** keeps its current coverage: the `jsonb_to_tsvector` term in V43's
  index is replaced by a tsvector over `value_text`. Parsed numerics drop out of FTS —
  but bare numbers are already invisible to it today, so nothing regresses; they gain the
  far better parametric path instead.

## Migration plan — incremental, no big-bang

1. **V50**: create `part_spec_value` + `spec_definition.unit_family`. Ship the write
   funnel **dual-writing**: rows *and* the JSONB, JSONB still authoritative for reads.
   Nothing user-visible changes; a bug in the new path cannot lose data.
2. **Assign unit families** to the NUMBER definitions (start from the 96 that already
   declare a unit; the V41 group taxonomy is a good map of what belongs to which family).
   Leave ambiguous ones (`data_size` etc.) family-less — their values stay text.
3. **Backfill** via a CLI runner (same pattern as the datasheet backfill: own profile,
   `web-application-type: none`, resumable, dry-run default). Parses existing JSONB
   through `MetricUnitParser` per definition; reports the unparseable residue grouped by
   distinct value, like convert-to-number's dry run. Eyeball that list; the override
   mechanism (original → replacement) is the model for fixing stragglers.
4. **Flip reads**: DTO assembly, the sparse-specs count, and the FTS term move to the
   table. Stop writing the JSONB.
5. **Parametric search UI**: spec criteria in the Parts page "More search options" panel
   (pick a definition → operator → value with metric-prefix input for NUMBER families).
6. **Drop** `part.specs` and rebuild V43's index without the jsonb term (a later
   migration, once step 4 has soaked).

Steps 1–3 are safe to ship independently and reversibly; the point of no return is step 6.

## Decisions (were open questions)

- **BOOLEAN is `value_text` `'true'`/`'false'`.** Booleans are filtered by equality, never
  by range, so they need no numeric column — and one text index serves them alongside
  TEXT/SELECT.
- **The AI prompt keeps asking for human strings, not bare numbers.** `SpecFieldCatalog`
  goes on offering `"100 nF"`; the intake boundary parses. Telling the model a base unit
  and trusting it to convert would put the conversion in the least verifiable place in the
  system, and would make the AI path disagree with every other source (Partsbox, the
  component cache, hand entry) which all send the human form.
- **Multi-unit definitions: nothing to decide — none exist.** Checked against the
  catalogue: **zero** `spec_definition` rows have a comma in `unit`, in any organisation.
  The comma-separated selector lives only in the frontend (`PartEditModal.tsx`,
  `QuickAdd.tsx` split `unit` on commas and render a number input + unit dropdown, storing
  `"<num> <unit>"`); nothing has ever configured one. The rewrite neither supports nor
  removes it — should one appear later, its values simply stay `value_text` under the rule
  above (no single base unit ⇒ no parse).

### What `unit` actually holds today

The whole declared-unit population, identical in all three organisations:

| unit | defs/org | fields |
|---|---|---|
| `m` | 19 | `length` `width` `height` `depth` `diameter` `pitch` `leadpitch` `thickness` `platingthickness` `switchtravel` `contactpitch` `rowspacing` `stackheight` … |
| `A` | 13 | `forwardcurrent` `supplycurrent` `dccurrent` `contactcurrentrating` `continuousdraincurrent_id_` `averagerectifiedcurrent` `coilcurrent` `fusecurrent` … |

32 distinct fields × 3 organisations = the 96 unit-bearing NUMBER definitions, all with
`metric_prefix = true`, spanning exactly **two** families (`length`, `current`).

Two consequences for step 2 of the migration:

- **The head start is smaller than "96 definitions" sounds** — it is 32 fields in 2
  families. The remaining ~530 NUMBER definitions per organisation need a family assigned
  from scratch, and the V41 group taxonomy is the only existing map of what belongs where.
- **Assigning families is a data-quality pass, not only a parser pass.**
  `currenttransferratio` is declared in `A` today, which is wrong — CTR is a dimensionless
  percentage. Expect more of these to surface; the backfill's unparseable-residue report
  (grouped by distinct value, like convert-to-number's dry run) is where they will show up
  and should be read that way.
- **The name is not the family, and the RKM families are where that bites.** Not one of the
  16 definitions matching `resist|capacit|induct|impedance` declares a unit today — every
  one is a bare number of undefined magnitude — so all three RKM families have to be
  assigned from nothing. Three in that list must **not** get the family their name suggests:
  `naturalthermalresistance` is °C/W, not Ω; `inductancetolerance` is a percentage;
  `numberofresistors` is a count. A regex over `json_name` would get all three wrong, so the
  assignment is by hand (as V41's group taxonomy was), not generated.
