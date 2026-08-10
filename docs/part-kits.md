# Part Kit Templates

Part of the Clele documentation — `CLAUDE.md` holds the overview and the index of these files; `API.md` lists the REST endpoints.

## Part Kit Templates

Parts are often bought in bulk as a kit: thirty resistors that share manufacturer, footprint,
tolerance and power rating and differ only in resistance. A **kit template** is the part form filled
in once, with the placeholder `${value}` wherever the varying value belongs, plus the list of
values. "Generate parts" expands the two into real parts with stock. Package: `PartKitTemplate` /
`PartKitTemplateValue` + `PartKitTemplateService` + `PartKitTemplateController`
(`/api/part-kit-templates`, class-level `PARTS_EDIT`).

- **Every stored field is TEXT, including the ones the part types more strictly.** `"10k"` is not a
  number and `"…/${value}.pdf"` is not a URL until it has been expanded, so the template columns
  cannot carry the part's own types. The generated part is what gets them. The same reasoning drives
  the UI: **the template editor renders every spec as a plain text input** whatever the definition's
  `dataType` is — a number field or a dropdown could not accept a placeholder at all.
- **The part number template must contain `${value}`** (400 otherwise, enforced in
  `validate` and mirrored as a disabled Save in the editor). Part numbers are unique per
  organisation, so a template whose part number does not vary would generate one part however many
  values it lists, silently piling every value's stock onto it.
- **Generating finds before it creates, and never rewrites a part it found.** A kit is bought more
  than once: the second pack must add stock to the parts already there, not fail on the unique part
  number and not overwrite a description someone has since corrected by hand. A template describes
  how a part is *born*, not what it must keep looking like. A new part's stock is `INITIAL`, an
  existing one's is `PURCHASE` — the same distinction the manual paths draw — and both movements
  name the template in their comment.
- The whole run is **one transaction**: a half-generated kit is worse than none, since nothing in
  the parts list says which values were reached.
- **Values are sent as the whole list**, and `applyValues` rewrites it by *reusing the rows that
  survive* rather than clearing and re-adding. `orphanRemoval` plus the unique `(template_id, value)`
  means a delete-then-insert of the same value inside one transaction can hit the constraint before
  the delete is flushed. Blanks and duplicates are dropped server-side, so the stored order cannot
  disagree with what the user saw.
- **A template carries images, and every part it generates is given them** (V49,
  `PartKitTemplateImageService`, `/api/part-kit-templates/{id}/images`). A kit's parts look alike —
  thirty resistor values differ in a printed number, not in a photograph — so the picture is chosen
  once. It is **linked, never copied**: each generated part points at the same `part_attachment`
  row, which is what the shared-attachment model (V46) was for. Uploads go through
  `PartAttachmentService.storeContent`, so a picture the organisation already holds is reused
  rather than stored again, and `description` records the kit name as its origin.
  - Images go **only to the parts the run creates**, the same rule every other field follows: a
    template says how a part is born. Removing an image from the template likewise leaves the parts
    already generated holding theirs.
  - Capped at 5, matching the per-part photo cap — a template that offered more could not apply
    them all.
- Specs land through `SpecDefinitionService.canonicalizeKeys` and tags through
  `TagService.resolveOrCreate`, exactly as every other intake path — a tag may hold `${value}` too.
- ⚠️ **Do not spell the placeholder in a migration.** `${…}` is Flyway's own placeholder syntax and
  an unknown name fails the migration, comments included.
- **Frontend**: `pages/PartKitTemplates.tsx` (`/part-kits`) lists the templates with **Generate
  parts** per row — a dialog asking quantity per value, location (pre-selected from the user's
  last-used) and an optional unit price, which then reports per value which part was created and
  which already existed. `pages/PartKitTemplateEdit.tsx` (`/part-kits/new`, `/part-kits/:id`) is the
  two-section editor. The part template on the left gains an **Images** block — thumbnails with a
  per-image ×, an upload button and **Find image**, the same web-image search Part Detail uses
  (extracted into `components/FindImageModal`, which both screens now share). Images are stored
  against the template, so on `/part-kits/new` the block says to save the kit first. On the right is
  the value list, where values
  are added through a small textarea — Enter adds, Shift+Enter is a newline, and a pasted list is
  split **one value per line**, since a kit's contents are normally copied out of a spreadsheet or
  a supplier's page rather than typed — and removed with their × but **never edited**, since editing
  one would silently orphan the part a previous run generated from it. A duplicate in a paste is
  skipped with a note naming it, not refused: losing the other twenty-nine to one repeat would be
  the worse failure. A preview of what the first
  value produces sits under the list.

### Undoing a generation

Generating is the one action in this app that creates dozens of rows from a single click, and
getting the quantity or the location wrong used to mean deleting thirty parts by hand — with nothing
in the catalogue saying which parts had come from the run. **Every run is now recorded** (V54,
`PartKitGeneration` + `PartKitGenerationItem`, written by `generate` itself) and **the most recent
one can be taken back whole** (`PartKitGenerationService`, `POST /{id}/generations/{genId}/undo`).

- **Undo is not a general reversal — it is the narrow claim that nothing has happened since**, and
  every condition is checked before anything is deleted. A refusal is a 409 naming its reason, and
  the same reason rides along with each run in `GET /{id}/generations` so the disabled button can
  say why. A greyed-out control that will not explain itself is indistinguishable from a broken one.
  - **Only the kit's newest run.** Undoing an earlier one would leave the later runs standing on
    parts and stock it had just removed.
  - **The stock is still exactly as the run left it**, per line: the movement it wrote is still the
    last thing that happened to that part *anywhere*, and the entry still holds `quantityBefore +
    quantityAdded`. Note this makes a consume-then-restock refuse even though the numbers match —
    a compensating movement is still a later movement, and treating the two as equivalent is how an
    undo silently reverses somebody else's correction.
  - **No part it created is used in a project** — `project_part`, `project_stock` or a matched
    `project_bom_line`. Those are decisions made about the part after it existed.
  - **Nothing it made has vanished by another route**, which the `ON DELETE SET NULL` FKs surface.
- **Parts the run *found* are never deleted** — they existed before it and must outlive it; only the
  stock it added comes off them. Edits to a generated part are deliberately **not** checked: the
  test the design commits to is about stock and project use.
- ⚠️ **`unit_price_before` is recorded at generate time because the weighted-average cost is not
  invertible.** `StockMovementService` recomputes the WAC on every add; there is no arithmetic that
  recovers the previous figure afterwards once other movements exist. Verified against a live
  instance: an entry at `108 @ 0.93` became `118 @ 0.86` and came back to `108 @ 0.93` exactly.
- **The undo reverses the `stock_movement` rows, not `generation.location`** — a location merge
  re-points movements, and the header's pointer would not follow. The header's location is display.
- **A kit template's images survive an undo.** The generated part links to the same
  `part_attachment` row the template holds, and `deleteOrphans` counts a kit template as a user of
  the content — sweeping on part links alone would delete exactly the picture the kit hands out.
- **Deleting the template deletes its history** (`ON DELETE CASCADE`), so those runs can no longer
  be undone. The delete confirmation says so.
- **Frontend**: a **History** button per row on `pages/PartKitTemplates.tsx` opens a modal listing
  the runs newest-first — when, by whom, quantity/price/location, the created/existing/units
  counts, an expandable per-value table, and **Undo** on the newest (replaced by its blocked reason
  otherwise). The confirmation spells out how many parts will be deleted and how many kept.
