# Database structure — `jlcparts`

An electronics parts catalogue: 585,152 components with descriptions, quantity-break
pricing, and 8.6M typed attribute values, organised under a two-level category tree.

PostgreSQL 16. Total size 3708 MB. All objects live in `public` and are prefixed `cc_`.
Requires the `pg_trgm` extension.

| object | kind | rows | size |
| --- | --- | ---: | ---: |
| `cc_components` | table | 585,152 | 1973 MB |
| `cc_component_attributes` | table | 8,588,047 | 1175 MB |
| `cc_prices` | table | 3,525,796 | 455 MB |
| `cc_attribute_value` | table | 242,485 | 91 MB |
| `cc_category_attributes` | materialized view | 20,632 | 3104 kB |
| `cc_attribute_def` | table | 1,718 | 648 kB |
| `cc_raw_categories` | table | 908 | 160 kB |
| `cc_categories` | table | 736 | 128 kB |
| `cc_import_meta` | table | 8 | 32 kB |
| `cc_parts` | view | — | — |
| `cc_component_attribute_values` | view | — | — |
| `cc_price_at(text, int)` | function | — | — |

## Entity relationships

```
cc_categories ──< cc_raw_categories       vendor categories folded into each
      │                                   normalised category
      │
      └──< cc_components ──< cc_prices    quantity-break price ladder
                  │
                  └──< cc_component_attributes >── cc_attribute_value >── cc_attribute_def
                                                   (the value)            (name + type)
```

`cc_component_attributes` is the junction between a part and its attributes. It carries
**two** links: `def_id` (which attribute) and `value_id` (what value). `def_id` is
derivable from `value_id`, but is stored so that "which parts have attribute X at all"
needs no join to the value table. A composite foreign key
`(value_id, def_id) → cc_attribute_value (id, def_id)` guarantees the two can never
disagree.

---

## Tables

### `cc_components`

One row per part, keyed by its LCSC code.

| column | type | null | notes |
| --- | --- | --- | --- |
| `lcsc` | text | no | **PK.** Catalogue code, e.g. `C2053235` |
| `lcsc_id` | integer | no | Numeric part of `lcsc`, for numeric ordering |
| `mfr` | text | yes | Manufacturer **part number** (MPN), e.g. `ATTINY402-SSFR` |
| `description` | text | yes | Vendor free-text; mixed English/Chinese, contains `℃`, `、`, `√` |
| `joints` | smallint | yes | Solder joints / pin count |
| `stock` | integer | yes | Units on hand at export time |
| `category_id` | integer | yes | → `cc_categories(id)` |
| `datasheet` | text | yes | Absolute URL |
| `image_key` | text | yes | Filename only; see `image_url` |
| `url_slug` | text | yes | Vendor URL fragment; see `product_url` |
| `price` | jsonb | yes | Raw ladder `[{"qFrom":1,"qTo":9,"price":1.5033}, ...]`, `qTo` null = open-ended |
| `price_qty1` | numeric(14,6) | yes | Unit price at quantity 1 |
| `price_min` | numeric(14,6) | yes | Best unit price at any quantity |
| `image_url` | text | — | **Generated.** `https://assets.lcsc.com/images/lcsc/900x900/` + `image_key`, else NULL |
| `product_url` | text | — | **Generated.** Product page from `url_slug` + `lcsc`; falls back to a search URL when `url_slug` is NULL |
| `manufacturer` | text | yes | Manufacturer **name**, e.g. `Microchip Tech` |
| `package` | text | yes | e.g. `SOIC-8`, `0402` |
| `basic_extended` | text | yes | Assembly library class: `Basic` / `Extended` |
| `status` | text | yes | e.g. `Active` |
| `search` | tsvector | — | **Generated.** `simple` config; `lcsc` and `mfr` weighted A, `description` weighted B |

`manufacturer`, `package`, `basic_extended` and `status` also exist as ordinary
attributes. They are duplicated onto the row because nearly every query filters on
them and the join is not worth paying each time.

Note `mfr` is the *part number* and `manufacturer` is the *company* — the names are
easy to transpose.

**Indexes** — PK on `lcsc`; btree on `category_id`, `(category_id, lcsc)`, `mfr`,
`manufacturer`, `package`, `basic_extended`, `lcsc_id`, `price_min`;
partial btree on `stock DESC WHERE stock > 0` and `(category_id, price_min) WHERE stock > 0`;
GIN trigram on `mfr` and `description`; GIN on `search`.

### `cc_prices`

The quantity-break ladder, one row per break. Prices are USD per piece.

| column | type | null | notes |
| --- | --- | --- | --- |
| `lcsc` | text | no | **PK** part 1 → `cc_components(lcsc)` ON DELETE CASCADE |
| `qty_from` | integer | no | **PK** part 2. Lower bound, inclusive |
| `qty_to` | integer | yes | Upper bound, inclusive. **NULL = no upper bound** |
| `price` | numeric(14,6) | no | Unit price in this bracket |

**Indexes** — PK `(lcsc, qty_from)`; btree `(qty_from, price)`.

### `cc_categories`

The normalised two-level tree. 736 rows.

| column | type | null | notes |
| --- | --- | --- | --- |
| `id` | integer | no | **PK** |
| `category` | text | no | Top level, e.g. `Embedded Processors and Controllers` |
| `subcategory` | text | no | Second level, e.g. `Microcontroller Units (MCUs/MPUs/SOCs)` |
| `component_count` | integer | yes | Parts in this category |

### `cc_raw_categories`

The original vendor categories, several of which fold into one normalised category
(e.g. `Amplifiers/Precision OpAmps`, `Amplifiers/Comparators/Precision Op Amps` and
`Operational Amplifier/Comparator/Precision OpAmps` all map to `Precision Op Amps`).
Provenance only — `cc_components` does not reference this table.

| column | type | null | notes |
| --- | --- | --- | --- |
| `id` | integer | no | **PK** |
| `category` | text | no | Vendor top level |
| `subcategory` | text | no | Vendor second level |
| `category_id` | integer | yes | → `cc_categories(id)` it folds into |
| `component_count` | integer | yes | |

### `cc_attribute_def`

What an attribute **is**: its name and the type of value it carries. 1,718 rows.

| column | type | null | notes |
| --- | --- | --- | --- |
| `id` | integer | no | **PK**, identity |
| `name` | text | no | e.g. `Capacitance`, `Gain Bandwidth Product` |
| `unit` | text | no | Unit family — see below |

**`UNIQUE (name, unit)`** — the key is the pair, not the name alone. 69 names carry more
than one type and are genuinely different attributes: `Sensitivity` exists as `decibel`,
`decibel_milliwatt`, `voltage_per_current`, `voltage_per_g`, `current_per_current` and
`voltage_per_magnetic_flux_density`. So `WHERE name = 'Sensitivity'` can match six
definitions; add `unit` when the distinction matters.

**Unit families** (54). Two are non-numeric — `identifier` (236 definitions) and `string`
(235) — and hold text in `value_text`. The rest are numeric and populate `value_num` /
`value_exact`:

`voltage` (223), `count` (196), `current` (163), `length` (135), `time` (71),
`frequency` (66), `decibel` (50), `resistance` (42), `capacitance` (34), `percentage` (32),
`ratio` (28), `power` (27), `temperature` (26), `angle` (14), `data_size` (14),
`data_rate` (11), `ppm` (11), `decibel_milliwatt` (10), `inductance` (8), `awg` (7),
`energy` (7), `magnetic_flux_density` (7), `slew_rate` (6), `kelvin` (5), `area_mm2` (5),
`force` (5), `temperature_coefficient` (4), `lsb` (4), `luminous_intensity` (3),
`pressure` (3), `luminous_flux` (3), `charge` (2), `voltage_noise_density` (2),
`current_temperature_drift` (2), `voltage_temperature_drift` (2), `rotational_speed` (2),
`melting_i2t` (2), and 17 further families with a single definition each.

> **Numeric values are in SI base units.** Volts, amps, ohms, farads, henries, hertz,
> seconds, watts, joules, coulombs, **metres**, tesla, candela, degrees Celsius.
> 100 nF is `1e-7`; a 1 mm length is `0.001`; 16 MHz is `16000000`.

**Indexes** — PK on `id`; UNIQUE `(name, unit)`; btree on `name` and `unit`;
GIN trigram on `name`.

### `cc_attribute_value`

What a part actually measures. One row per **distinct value** of a definition, shared by
every part with that value — each row is referenced ~35 times on average. 242,485 rows.

| column | type | null | notes |
| --- | --- | --- | --- |
| `id` | integer | no | **PK** |
| `def_id` | integer | no | → `cc_attribute_def(id)` |
| `format` | text | yes | Template over slot names, e.g. `${Rds} @ ${Vgs}, ${Id}` |
| `primary_slot` | text | yes | Which slot is promoted to the flat columns below |
| `display` | text | yes | `format` rendered with units, e.g. `15mR @ -, -`, `2.2 MHz`, `4k7` |
| `value_num` | double precision | yes | Primary slot when numeric |
| `value_exact` | numeric | yes | `value_num` rounded to 12 significant digits |
| `value_text` | text | yes | Primary slot when `identifier` / `string` |
| `slots` | jsonb | no | **All** slots, typed |

Every row has exactly one of `value_num` (132,801 rows) or `value_text` (109,684); none
has neither.

> **Use `value_exact` for `=` and `IN ()`; use `value_num` for `<`, `>`, `BETWEEN`.**
> The source numbers are JSON doubles, so 100 nF is stored as `1.0000000000000001e-07`
> and `WHERE value_num = 1e-7` silently returns **nothing**. `value_exact` compares as
> expected. Some values (1 µF = `1e-6`) happen to be exactly representable and work
> either way — that is luck, not a rule.

**`slots`** holds every value parsed from the vendor's spec string, each with its own
unit family:

```json
{"Rds": {"unit": "resistance", "value": 0.015},
 "Vgs": {"unit": "voltage",    "value": "NaN"},
 "Id":  {"unit": "current",    "value": "NaN"}}
```

173,840 values have a single slot; the remaining 68,645 have 2–10. `format` and
`primary_slot` are per-value, not per-definition — a part listing 3 features has a
different template than one listing 8 — which is why they live here rather than on
`cc_attribute_def`.

Three consequences:

1. **The primary slot is positional, not semantic.** For `Dual Supply` the primary is
   `voltage 1 min`, meaning "whichever range the vendor listed first". One row displays
   `1 V ~ 18 V, -18 V ~ -1 V` with `value_num = 1`; another displays
   `-15 V ~ -1.5 V, 1.5 V ~ 15 V` with `value_num = -15`. Range filters on `value_num`
   are meaningless for such attributes — read the intended slot out of `slots`.
2. **Missing values are the string `"NaN"`**, not JSON null — 12,433 rows have at least
   one such slot, rendered as `-` in `display`. `'NaN'::float8` is a valid float and will
   poison comparisons, so exclude them explicitly.
3. **The same concept may be parsed or unparsed.** `Drain-Source On Resistance (RDS(on))`
   is structured (`unit = resistance`, `value_num = 0.015`), while
   `Drain-Source On Resistance (RDS(on) @ Vgs, Id)` is raw text
   (`unit = string`, `value_text = '27mΩ@10V,4.5V'`). These are two separate definitions;
   a numeric filter finds only the first.

**Indexes** — PK on `id`; UNIQUE `(id, def_id)` (target of the composite FK);
partial btree on `(def_id, value_exact)`, `(def_id, value_num)`, `(def_id, value_text)`,
each `WHERE ... IS NOT NULL`; GIN trigram on `display`.

### `cc_component_attributes`

The part ↔ attribute junction. 8,588,047 rows.

| column | type | null | notes |
| --- | --- | --- | --- |
| `lcsc` | text | no | **PK** part 1 → `cc_components(lcsc)` ON DELETE CASCADE |
| `def_id` | integer | no | **PK** part 2 → `cc_attribute_def(id)` |
| `value_id` | integer | no | → `cc_attribute_value(id)` |

**`PRIMARY KEY (lcsc, def_id)`** enforces the real rule: a part carries at most one value
per attribute definition. This makes `max(...) FILTER (WHERE attribute = '...')` pivots
safe.

The composite FK `(value_id, def_id) → cc_attribute_value (id, def_id)` keeps `def_id`
consistent with the definition its `value_id` belongs to.

The table records no ordering — attributes have no inherent sequence here, and no
grouping or sectioning of attributes exists anywhere in this database.

**Indexes** — PK `(lcsc, def_id)`; btree `(value_id, lcsc)` (the hot path: resolve a
value, then find its parts); btree `(def_id, lcsc)`.

### `cc_import_meta`

Provenance, as `key`/`value` text pairs: `source`, `manifest_version`,
`manifest_created`, `manifest_total_components`, `imported_components`,
`imported_price_breaks`, `imported_attribute_links`, `imported_at`.

`manifest_created` is the snapshot date — **all stock levels and prices are as of that
moment**.

---

## Views

### `cc_parts`

`cc_components` with category names resolved. The everyday starting point. Exposes
`lcsc`, `mfr`, `manufacturer`, `description`, `category`, `subcategory`, `package`,
`basic_extended`, `status`, `joints`, `stock`, `price_qty1`, `price_min`, `datasheet`,
`image_url`, `product_url`, `category_id`.

It is a `LEFT JOIN`, so parts with no category still appear. Omits `price`, `search`,
`lcsc_id`, `image_key` and `url_slug` — query `cc_components` for those.

### `cc_component_attribute_values`

Flattened part ↔ attribute: `lcsc`, `def_id`, `value_id`, `attribute` (the name),
`unit`, `display`, `value_num`, `value_exact`, `value_text`, `slots`. Saves writing the
three-table join by hand.

### `cc_category_attributes` (materialized)

Which attributes each category carries, how many parts have them, and the numeric range
they span: `category_id`, `def_id`, `attribute`, `unit`, `part_count`,
`distinct_values`, `min_value`, `max_value`.

**This is how you discover what a category can be filtered by.** Attribute names are not
consistent across categories — capacitors have `Rated Voltage`, resistors have `Power`,
MOSFETs have `Power Dissipation (Pd)`. Look names up here rather than guessing.

Not automatically maintained: `REFRESH MATERIALIZED VIEW cc_category_attributes;` after
changing data.

## Function

### `cc_price_at(p_lcsc text, p_qty int) → numeric`

Unit price for an order of `p_qty` pieces — the `cc_prices` bracket containing `p_qty`.
Returns NULL if the part has no ladder. `STABLE`.

---

# Example queries

## Look up one part

```sql
SELECT * FROM cc_parts WHERE mfr = 'ATTINY402-SSFR';

SELECT attribute, unit, display, value_num, value_text
FROM cc_component_attribute_values
WHERE lcsc = 'C2053235'
ORDER BY attribute;

SELECT qty_from, qty_to, price FROM cc_prices
WHERE lcsc = 'C2053235' ORDER BY qty_from;
```

## Search

```sql
-- Ranked full-text over part number + description
SELECT lcsc, mfr, manufacturer, stock, price_qty1
FROM cc_components
WHERE search @@ websearch_to_tsquery('simple', 'LM358')
ORDER BY ts_rank(search, websearch_to_tsquery('simple', 'LM358')) DESC, stock DESC
LIMIT 10;

-- Typo-tolerant MPN lookup, via the trigram index
SELECT lcsc, mfr, manufacturer, stock,
       round(similarity(mfr, 'STM32F103C8')::numeric, 3) AS sim
FROM cc_components
WHERE mfr % 'STM32F103C8'
ORDER BY similarity(mfr, 'STM32F103C8') DESC
LIMIT 10;
```

## Filter by attribute value

```sql
-- Every part with a capacitance of exactly 1 uF (farads -> 1e-6)
SELECT p.lcsc, p.mfr, p.manufacturer, p.subcategory, p.package,
       v.display AS capacitance, p.stock, p.price_min
FROM cc_parts p
JOIN cc_component_attributes ca ON ca.lcsc = p.lcsc
JOIN cc_attribute_def   d ON d.id = ca.def_id
JOIN cc_attribute_value v ON v.id = ca.value_id
WHERE d.name = 'Capacitance'
  AND v.value_exact = 1e-6
ORDER BY p.price_min NULLS LAST, p.lcsc;
```

Several independent conditions read best as one `EXISTS` each — the planner resolves
each predicate against the small definition and value tables first, then intersects part
lists through `(value_id, lcsc)`:

```sql
-- 100 nF 0402 X7R capacitors rated 16 V or better, in stock, cheapest first
SELECT p.lcsc, p.mfr, p.manufacturer, p.stock, p.price_min
FROM cc_parts p
WHERE p.package = '0402'
  AND p.stock > 0
  AND EXISTS (SELECT 1 FROM cc_component_attribute_values v
              WHERE v.lcsc = p.lcsc AND v.attribute = 'Capacitance'
                AND v.value_exact = 1e-7)
  AND EXISTS (SELECT 1 FROM cc_component_attribute_values v
              WHERE v.lcsc = p.lcsc AND v.attribute = 'Rated Voltage'
                AND v.value_num >= 16)
  AND EXISTS (SELECT 1 FROM cc_component_attribute_values v
              WHERE v.lcsc = p.lcsc AND v.attribute = 'Temperature Coefficient'
                AND v.value_text = 'X7R')
ORDER BY p.price_min
LIMIT 25;
```

When you need the matched value in the output, join instead of using `EXISTS`:

```sql
-- Op-amps with gain-bandwidth product above 10 MHz and rail-to-rail output
SELECT p.lcsc, p.mfr, p.manufacturer, p.package,
       gbw.display AS gain_bandwidth, p.stock, p.price_qty1
FROM cc_parts p
JOIN cc_component_attribute_values gbw
  ON gbw.lcsc = p.lcsc AND gbw.attribute = 'Gain Bandwidth Product'
WHERE p.category = 'Amplifiers and Comparators'
  AND gbw.value_num > 1e7
  AND p.stock > 0
  AND EXISTS (SELECT 1 FROM cc_component_attribute_values v
              WHERE v.lcsc = p.lcsc AND v.attribute = 'Rail to Rail'
                AND v.value_text ILIKE '%Output%')
ORDER BY gbw.value_num DESC
LIMIT 25;

-- MOSFETs: Vds >= 60 V, Rds(on) < 20 mohm, in stock
SELECT p.lcsc, p.mfr, p.package,
       vds.display AS vds, rds.display AS rds_on, p.stock, p.price_min
FROM cc_parts p
JOIN cc_component_attribute_values vds
  ON vds.lcsc = p.lcsc AND vds.attribute = 'Drain to Source Voltage'
JOIN cc_component_attribute_values rds
  ON rds.lcsc = p.lcsc AND rds.attribute = 'Drain-Source On Resistance (RDS(on))'
WHERE vds.value_num >= 60
  AND rds.value_num < 0.02
  AND p.stock > 0
ORDER BY rds.value_num
LIMIT 25;
```

## Reach a non-primary slot

```sql
-- Rds(on) measured at a stated Vgs, excluding unstated ("NaN") conditions
SELECT v.display,
       (v.slots->'Rds'->>'value')::float8 AS rds,
       (v.slots->'Vgs'->>'value')::float8 AS vgs
FROM cc_attribute_value v
JOIN cc_attribute_def d ON d.id = v.def_id
WHERE d.name = 'Drain-Source On Resistance (RDS(on))'
  AND v.slots->'Vgs'->>'value' <> 'NaN'
ORDER BY rds
LIMIT 20;
```

## Discover what is filterable

```sql
-- Which attributes does a category carry, and over what range?
SELECT ca.attribute, ca.unit, ca.part_count, ca.distinct_values,
       ca.min_value, ca.max_value
FROM cc_category_attributes ca
JOIN cc_categories c ON c.id = ca.category_id
WHERE c.subcategory = 'Precision Op Amps'
ORDER BY ca.part_count DESC;

-- The distinct values of one attribute within one category
SELECT v.display, v.value_exact, count(*) AS parts
FROM cc_component_attributes ca
JOIN cc_attribute_def   d ON d.id = ca.def_id
JOIN cc_attribute_value v ON v.id = ca.value_id
JOIN cc_components      c ON c.lcsc = ca.lcsc
WHERE c.category_id = (SELECT id FROM cc_categories
                       WHERE subcategory = 'Multilayer Ceramic Capacitors MLCC - SMD/SMT')
  AND d.name = 'Rated Voltage'
GROUP BY v.display, v.value_exact
ORDER BY v.value_exact;

-- Where does an attribute name live, and is it parsed or raw text?
SELECT d.id, d.name, d.unit, count(*) AS parts
FROM cc_attribute_def d
JOIN cc_component_attributes ca ON ca.def_id = d.id
WHERE d.name ILIKE '%Rds%'
GROUP BY d.id, d.name, d.unit
ORDER BY parts DESC;
```

## Pricing

```sql
-- Unit and extended price for an order of 500
SELECT lcsc, mfr, cc_price_at(lcsc, 500) AS unit_price,
       round(cc_price_at(lcsc, 500) * 500, 2) AS order_total
FROM cc_components
WHERE lcsc IN ('C5208', 'C2053235');

-- Biggest volume discounts among well-stocked parts
SELECT lcsc, mfr, price_qty1, price_min,
       round(100 * (1 - price_min / price_qty1), 1) AS pct_off
FROM cc_components
WHERE price_qty1 > 0 AND price_min > 0 AND stock > 10000
ORDER BY pct_off DESC, price_qty1 DESC
LIMIT 20;
```

## Pivot attributes into columns

Safe as a plain aggregate because `(lcsc, def_id)` is the primary key, so no part can
carry the same attribute twice:

```sql
SELECT p.lcsc, p.mfr, p.package,
       max(v.display) FILTER (WHERE v.attribute = 'Resistance') AS resistance,
       max(v.display) FILTER (WHERE v.attribute = 'Tolerance')  AS tolerance,
       max(v.display) FILTER (WHERE v.attribute = 'Power')      AS power,
       p.stock, p.price_min
FROM cc_parts p
JOIN cc_component_attribute_values v ON v.lcsc = p.lcsc
WHERE p.subcategory ILIKE '%Chip Resistor%'
  AND p.package = '0603'
  AND p.stock > 100000
GROUP BY p.lcsc, p.mfr, p.package, p.stock, p.price_min
ORDER BY p.price_min
LIMIT 25;
```

## Catalogue overview

```sql
SELECT * FROM cc_import_meta ORDER BY key;

SELECT category, count(*) AS parts, count(*) FILTER (WHERE stock > 0) AS in_stock
FROM cc_parts GROUP BY category ORDER BY parts DESC;

-- Vendor categories folded into one normalised category
SELECT c.category, c.subcategory, r.category AS vendor_category,
       r.subcategory AS vendor_subcategory, r.component_count
FROM cc_categories c
JOIN cc_raw_categories r ON r.category_id = c.id
WHERE c.subcategory = 'Precision Op Amps';
```
