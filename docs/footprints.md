# Footprints — the managed list of package types

Part of the Clele documentation — `CLAUDE.md` holds the overview and the index of these files;
`API.md` lists the REST endpoints. Backend `service/FootprintService`, screen `pages/Footprints.tsx`
(`/footprints`), picker `components/FootprintInput.tsx`.

## What it is

`part.footprint` was free text, written by Partsbox, OctoPart, the AI lookup, kits, Quick Add and
the component cache, so the same package arrived as `SOT23`, `SOT-23`, `TO-236AB` and
`0805 (2012 Metric)`. V64 adds a per-organisation list (`footprint`) with aliases
(`footprint_alias`), and every footprint a part holds is now a name from it.

**`part.footprint` stays a text column, not a foreign key.** Every writer and DTO keeps working,
kit templates can still expand `${…}` into it, and what changes is only that the text goes through
`FootprintService` on the way in.

## Two spellings are one footprint when…

- **their match keys are equal**: lower case, letters and digits only, so `SOT23`, `sot-23` and
  `SOT 23` are all `sot23`. `FootprintService.matchKey`, the SQL in V64 and V65
  (`lower(regexp_replace(x, '[^A-Za-z0-9]', '', 'g'))`) and `footprintKey` in
  `utils/footprints.ts` must stay the same. A spelling with no letters or digits at all keys on
  itself;
- **or one is an alias of the other**, which covers what the key cannot: `TO-236` is SOT-23,
  `0805 (2012 Metric)` and KiCad's `R_0805_2012Metric` are 0805;
- **or, failing both, the part before a trailing `(...)` matches**: distributors name a second
  outline in parentheses, so `SMA(DO-214AC)` is SMA and `SOD27(DO-35)` is DO-35.

## What is not a package

The component cache fills the package field of about a fifth of its parts with something that is
not a package: `插件` ("through-hole"), alone or with a size (`插件,P=7.5mm`), `SMD`, `-`, or
`SMD,19.2x18mm`. `FootprintService.isPackageName` says no to those — CJK text, a leading `SMD,`,
or a match key of nothing or a bare mounting word (`smd`, `tht`, `throughhole`, `none`, …). Such a
spelling is stored as **no footprint** rather than added to the list; MCP `add_part` refuses it,
the Footprints screen will not create one, and the picker says "Not a package type". V65's
`fp_junk`, and `isPackageName` in `utils/footprints.ts`, are the same rule.

The match key is **unique across every name and alias of an organisation** (enforced in the service,
409 "merge the two instead"), so a lookup can never land on two footprints. An alias whose key equals
its footprint's own key is dropped as redundant.

⚠️ **Chip sizes are imperial, and a bare metric code is never an alias**: metric 0603 is imperial
0201. Only the spelled-out forms (`1608 Metric`, `0603 (1608 Metric)`) are aliases.

## How strict each writer is

| writer | unknown footprint |
|---|---|
| Part edit, Quick Add, OctoPart/AI/cache apply, kit generation | `canonicalize`: a known spelling becomes the canonical name, an unknown one is **added to the list** — the list stays a complete record of what is in use — and what is not a package becomes none |
| MCP `add_part` | `match`: an unknown one is **refused** with the closest names, unless the call says `newFootprint: true` |
| Partsbox importer | not routed: it has no current organisation, and it is a one-off |

The screens are lenient because a person typing a new package is usually right. The MCP tool is strict
because a model will invent a plausible package name nobody uses. The picker (`FootprintInput`)
offers the list and says what a typed spelling will be stored as ("Saved as SOT-23-5"), or that it is
new.

On `PUT /parts/{id}` an omitted `footprint` is left alone and a blank one clears it, so a client
written before the field existed does not wipe it on every save.

## Changing the list

- **Rename** moves every part carrying the old name and keeps the old name as an alias.
- **Merge** folds one footprint into another: its parts move, and its name and aliases become the
  target's aliases. This is how two names the match key could not see as one are put together.
- **Delete** only works for a footprint no part uses. One in use is merged instead.

New organisations get a copy of the template organisation's list, aliases included.

## The V64 seed

1. Part footprints are trimmed, and blank becomes NULL.
2. ~75 standard packages are inserted for every organisation, with their aliases: chip sizes 01005
   to 2512, SOT/SOD/SC, SMA/SMB/SMC/MELF, TO-92/220/247/252/263, SOIC/MSOP/TSSOP/SSOP, DFN/QFN,
   TQFP/LQFP and DIP.
3. Each spelling in use is grouped. A spelling that matches a standard package (by name or alias
   key) becomes that package. Everything else groups by match key, under the spelling most parts
   already use.
4. Parts are rewritten to their group's canonical name.

Things the key cannot match (`Radial D5mm` vs `Radial 5mm`) stay separate and are merged by hand on
the Footprints screen.

## The V65 standard list

V65 widens the seed to ~250 packages, for older and through-hole stock: metal cans (TO-5/18/39/46/
72/99/100, TO-3, TO-66), more power packages, SIP, PLCC, wide SOIC, more QFN/QFP/SSOP, crystals
(HC-49, DT-26/38, the SMD 2016…7050 sizes), bridge rectifiers, DIN axial resistor sizes, radial
electrolytic cans, tantalum EIA cases, trimmers (3296W, RM-065 — assigned by part number, since the
cache labels them only "插件" with a size) and valve sockets (B7G, B9A, K8A, …, with Noval/Octal as
aliases). Names follow JEDEC/IPC-7351 in the short form distributors use; the registered outline
(`TO-213AA`, `DO-204AL`) is an alias.

⚠️ **DIP width**: a plain DIP name is the **300 mil** one. DIP-24 and DIP-28 come in both widths,
and the **600 mil** one is its own footprint, `DIP-24W` / `DIP-28W` (aliases `WDIP-28`, `CDIP-28`,
`DIP-28-600mil`); `SPDIP`, `SKDIP` and `-300mil` spellings are aliases of the plain one. DIP-32 and
up only exist wide, so there the plain name is 600 mil. A part V64 had left on DIP-24 or DIP-28 had
no width, and in this stock those were all wide, so V65 moves them to the W footprint. Wide
(`WDIP`) and ceramic (`CDIP`) DIPs of other pin counts are aliases of the plain footprint.
Likewise SOIC-16 is the 3.9 mm body and `SOIC-16W` the 7.5 mm one; SOIC-18 and up only exist wide.

It then merges, per organisation, every footprint an alias or a standard name (or the part
before a trailing `(...)`) points at — `to66` → TO-66, `wdip24` → DIP-24W, `SPDIP-28` →
DIP-28 — the way the Footprints screen merges, and clears what is not a package off the
parts and the list. `804` (a resistor network) is cleared by name: no rule could tell.

⚠️ The migrations use temporary tables dropped at the end, not `ON COMMIT DROP`: Flyway runs a
migration in one transaction, but `psql` without `-1` does not, and the table would vanish after
its own `CREATE`.
