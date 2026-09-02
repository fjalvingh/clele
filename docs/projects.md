# Projects

Part of the Clele documentation — `CLAUDE.md` holds the overview and the index of these files;
`API.md` lists the REST endpoints. The uploaded BOM file and the screen that matches its lines to
parts are in `docs/bom-import.md`.

## Two lists, two names

A project shows exactly two lists of parts, and confusing them is what the naming rule exists to
prevent:

- the **project parts list** (`project_part`) — what the project needs and what it is holding. This
  is the project itself.
- the **imported BOM** (`project_bom` / `project_bom_line`) — an uploaded EDA export and the
  matching work on it. It lives on its own screen and feeds the parts list through *Apply to project
  parts list*.

"BOM" means the imported file and nothing else. Never call the parts list a BOM.

## Two phases, and where the parts are

`ProjectStatus` is **ACTIVE** or **CANCELLED**. The difference is physical:

- **ACTIVE** — every line of the parts list is *out of stock and in the project*. Putting a part on
  the list takes it off the shelf there and then; there is no state in which a project has a parts
  list but has not taken the parts. Everything is editable.
- **CANCELLED** — every allocation has gone back to the location it came from. **No input is
  accepted at all** (`ProjectService.requireActiveProject`, which every write goes through).

Cancelling keeps *what the project needs* and drops *what it holds*, which is why `project_part`
carries both:

| column | meaning |
|---|---|
| `qty_per_instance` | how many one build instance needs; the whole-build need is this × `project.instance_count` |
| `qty_allocated` | how many are out of stock right now. Zero while cancelled |

`qty_allocated` is the aggregate of that (project, part)'s `project_stock` batches, each of which
remembers the location it was drawn from — which is where a return puts it back.

Reactivating fetches the whole list out of stock again. It draws from wherever the stock is *now*,
not from the location it was returned to: stock moves while a project sits cancelled, and pinning
the old location would fail for a reason nobody could see.

## Short allocation is a state, not an error

`ProjectAllocationService` is the only place stock moves between the shelf and a project, so adding
a part by hand, applying an imported BOM and reactivating a cancelled project all behave the same.
Every method **returns how many it actually moved**.

⚠️ **Allocation never refuses because stock ran out.** It takes what there is, the caller stores that
in `qty_allocated`, and the line shows `shortfall = totalNeeded − qtyAllocated` with an amber row.
Refusing the whole operation because the shelf is one resistor short would make the feature useless
for exactly the case it exists to track — and there is nothing the user could do about it from that
screen anyway.

`allocate` draws from the **fullest location first**, so a part spread thin over several drawers is
emptied out of as few of them as possible. `release` walks the batches **newest first** and deletes
each as it empties, so `project_stock` always describes what the project is still holding.

Raising `instance_count` on an active project raises every line's need, so
`ProjectService.update` tops the allocation up in the same call. The alternative is a project
that silently reports itself short of parts that are sitting on the shelf.

## Returning one line

*Return* on a parts list row asks for a quantity when the project holds more than one and confirms
when it holds one, then puts them back. The line **stays on the list with its need intact** — so it
reads as short until the parts are fetched again. Returning is not the same as deciding the project
no longer needs the part; *Remove* is.

## Deleting a project

Only a cancelled project can be deleted, and the delete is **half logical**: the parts list, the
allocation batches and the imported BOM are deleted for real, while the `project` row is kept with
`deleted = true`. `stock_movement.project_id` still points at it, so the ledger goes on saying which
project a PROJECT_OUT or PROJECT_RETURN belonged to. Every query the SPA reaches filters
`deleted = false`, so the project is gone as far as the user is concerned.

A cancelled project holds no stock, so nothing of value is lost by hard-deleting the children.
