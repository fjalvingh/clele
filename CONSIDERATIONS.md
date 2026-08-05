# Considerations — fitting the app to its audience

An assessment of Sortiment against its intended audience: **electronics hobbyists managing their own
parts stock**. Written 2026-08-05 against the state of `master` at commit `53183c9`.

This is a product-fit review, not a code review. Everything below is grounded in the current code;
file references point at what was read.

---

## The core mismatch

The app is engineered like a small-business ERP — organisations, per-organisation permission
matrices, invitations with a pluggable mail-provider registry, a global-admin tier — but the
audience is one person at a bench with a phone in their hand. Five of the thirteen sidebar entries
are administration (`frontend/src/components/Layout.tsx:119-128`).

The highest-leverage improvements all come from closing that gap. None of them require tearing out
what exists; the multi-tenancy works and is genuinely needed by a makerspace or a small team. It
just should not be the first thing a solo user steps over.

---

## 1. It doesn't work at the bench

### No mobile layout at all

`Layout.tsx` has a fixed `w-64` sidebar and not a single responsive breakpoint outside one filter
grid in `Parts.tsx:489`. There is no hamburger, no drawer, no small-screen fallback.

Every activity a hobbyist performs *while soldering* is a phone activity:

- "Do I have this part?"
- "Which drawer is it in?"
- "Add the 50 resistors that just arrived."

This is the single biggest fix. It is also a prerequisite for the next one.

### No camera scanning

`pages/BarcodeScanner.tsx` is a focused text input (`autoFocus`, `inputRef` at lines 144, 173, 401,
407) waiting on a USB keyboard-wedge scanner. Hobbyists do not own one; they own a phone camera.

The `BarcodeDetector` API covers Chrome/Android natively, with a ZXing/quagga fallback elsewhere.
Paired with a mobile layout, this promotes the scanner from a niche feature to the primary entry
point into the app.

### Unit price is mandatory in the fastest path

`BarcodeScanner.tsx:110` — `disabled={!locationId || !unitPrice}`.

Quick Add (`QuickAdd.tsx:983`) and Part Detail do **not** require a price; only the scanner does.
Hobbyist stock is grab bags, salvage, sample kits and gifts, where the price is often genuinely
unknown. Forcing the field trains people to type `0`, which is worse than a null for any later
valuation. Make it optional and consistent with the other add paths.

---

## 2. You can't search for parts the way a hobbyist thinks about them

The search term matches `part_number ILIKE` plus a description full-text match, and nothing else
(`repository/PartRepository.java:32-38`). It does not cover `manufacturer`, `mpn`, `details`, or —
most importantly — **specs**.

That last omission is the striking one, given the investment behind it:

- 355 catalogued spec fields
- a 19-group taxonomy (V41)
- alias merging and duplicate consolidation (V40, V42)
- metric-prefix scaling and a TEXT→NUMBER conversion tool

None of it is reachable at the moment of use. A hobbyist searches for `10k 0805` or
`3.3v sot-23 regulator`. They know the **value and the package**, essentially never the MPN.

Two complementary fixes:

1. **Index specs into the full-text vector** (`jsonb_to_tsvector` over `part.specs`) so free text
   finds them. Cheap, and immediately useful.
2. **Faceted filters per category.** Selecting a category already implies which spec fields matter —
   that is exactly what the `category_spec` links encode. Pick "Resistors", get Resistance /
   Tolerance / Package as filter controls. This is what the taxonomy work was actually for.

---

## 3. Data can't get in or out

There is no CSV import, no export, and no backup action anywhere in the codebase (verified by
grepping both `frontend/src` and `backend/src/main/java`).

The Partsbox importer exists but is a developer-only CLI that requires capturing a WebSocket session
out of Chrome DevTools — not a path any user will take.

Hobbyists arrive **from** somewhere: a spreadsheet, PartsBox, an old KiCad BOM. And they are rightly
wary of lock-in when the data represents years of accumulated stock. A column-mapping CSV import plus
a full inventory export would do more for adoption than any new feature on this list.

---

## 4. Nothing tells someone how to run it

No README, no Dockerfile, no compose file at the repository root.

Standing the app up currently requires: Java 21 under a `mvn21` alias, Node, PostgreSQL, a Go
toolchain (for the print daemon), and an Anthropic API key. For a self-hosting audience,
`docker compose up` is the difference between trying the app and closing the tab.

---

## 5. The AI paths cost money the audience won't spend

Quick Add depends on an Anthropic API key (`application.yml:87`). OctoPart enrichment requires each
user to hold a personal Nexar contract capped at roughly 100 requests per month.

Two keyless answers, in order of value:

- **A "standard part" builder.** Pick Resistor → value → tolerance → package → quantity. E12 series,
  common capacitor values, the 74xx family, LM/NE jellybeans. No network, no key, no cost — and it
  covers the majority of a hobbyist's actual bin count.
- **Extend Ollama to part lookup.** It is already wired up for auto-categorization
  (`service/PartCategorizationService.java`), so the local-inference plumbing exists. Reusing it for
  lookup gives a fully offline fallback for free.

---

## 6. Projects stop short of the payoff

`pages/ProjectDetail.tsx` builds a BOM one part at a time, by hand.

The workflow that actually matters to this audience is:

> paste a KiCad / EasyEDA BOM → match lines against inventory → "you have 14 of these 21 lines,
> here is the shopping list for the rest"

The stock-pull and reservation mechanics are already built. What is missing is the **BOM import** and
the **gap report** — the two ends of the loop.

---

## Smaller, cheap wins

- **Location barcodes** (`LOC-000123`) reusing the existing Code128 encoder in `utils/code128.ts`,
  which is already generic. Scan a drawer, see its contents. Very high utility per line of code.
- **The Dashboard leads with the wrong number.** "Total Stock Value" is a business metric
  (`Dashboard.tsx:93-99`). What a hobbyist opens the app for is: recently added, recently consumed,
  parts missing a location / datasheet / photo, and a search box.
- **`Dashboard.tsx:98` and `:109` use emoji glyphs** (💰, ⚠️) while the other two cards use inline
  SVG. CLAUDE.md explicitly forbids emoji for exactly the font-fallback reason — these two should be
  SVG icons like their neighbours.
- **Hide the admin surface on solo installs.** Not a removal — just don't make a single user with a
  single organisation navigate past five administration entries to reach their parts.
- **Search `details` as well as `description`.** It is a free-text field users fill in and then
  cannot find.

---

## Suggested order of work

| # | Item | Why first |
|---|------|-----------|
| 1 | Mobile layout + camera scanning | Unlocks the actual use case; everything else is used more once this lands |
| 2 | Spec-aware search (FTS + category facets) | Redeems a large body of work already done and unused |
| 3 | CSV import/export + compose file | Adoption and trust — the two things blocking a new user at the door |
| 4 | Optional unit price in the scanner | One-line fix, removes a daily papercut |
| 5 | Standard-part builder | Removes the API-key dependency from the most common entry path |
| 6 | BOM import + gap report | Closes the Projects loop |
