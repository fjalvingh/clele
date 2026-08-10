# Stock, Thresholds & Locations

Part of the Clele documentation — `CLAUDE.md` holds the overview and the index of these files; `API.md` lists the REST endpoints.

## Stock Model

- `stock_entry` = on-hand aggregate (one row per part+location; read by dashboard,
  low-stock, part-detail). `stock_movement` = ledger of signed-delta movements (history) and the
  **source of truth**: the invariant `stock_entry.quantity == Σ stock_movement.quantity` holds per
  (part, location).
- **Every on-hand change funnels through `StockMovementService.apply(part, location, deltaQty,
  unitPrice, comments, type)`** — it writes one `StockMovement` (delta) and updates the `stock_entry`
  aggregate in the same transaction, checks the location is in the current organisation, and rejects changes that would drive
  stock negative. All manual paths route through it: `StockEntryService.create` (delta `+qty`,
  `INITIAL`), `update` (delta `new−old`, `ADJUST`; a price-only edit writes no movement),
  `delete` (delta `−qty`, `ADJUST`, then drops the row); `QuickAddService` (delta `+qty`, `INITIAL`);
  and `PartService.create` when the New Part dialog carried an opening amount (delta `+qty`,
  `INITIAL`, in the same transaction as the part — see *Creating a part with its stock* in this file).
- **Part detail stock operations** are the explicit user-facing verbs (no "Edit" — adjusting an
  absolute quantity was unclear): `StockEntryService.addStock` (delta `+qty`, `PURCHASE`, find-or-
  create, also (re)sets the price), `takeStock` (delta `−qty`, `CONSUME`), and `move`
  (transfer between two locations). Each is a `POST /api/stock/{add,take,move}`. **Move** writes two
  `MOVE` movements — a negative leg at the source and a positive leg at the destination, each with a
  comment naming the other location ("Moved to …" / "Moved from …") for a clear trace — and carries
  the source entry's unit price to the moved stock. Both locations must be in the current
  organisation (`StockMovementService.requireCurrentOrganisation`); since V36 there is no per-user
  restriction within one, so every member sees add/take/move/remove on every stock line.
- The Partsbox importer keeps its own dated-movement loop (movements tagged `IMPORT`, entry = Σ) — it
  was already consistent. `POST /api/stock/reconcile` (`PARTS_EDIT`) realigns every aggregate to its
  ledger and returns `{corrected: n}` — a verification/safety-net hook (expect 0 in steady state).

### Creating a part with its stock

The **New Part** dialog (Parts screen) carries an amount, a location and a per-item price under the
part's own fields, so a part that was *bought* rather than merely catalogued does not have to be
created and then stocked in a second step. All three are optional; the block is skipped entirely
when no amount is given, and an amount **requires** a location (a price never is — how many are on
hand is often known when what they cost is not). Enforced both places: the Save button refuses the
incomplete combination, and `PartService.create` answers it 400.

- **`PartCreateRequest extends PartRequest`, deliberately not three more nullable fields on the
  base.** `PUT /parts/{id}` takes the base type and therefore *cannot* carry stock at all, rather
  than carrying it and silently ignoring it — creating stock and editing a part are different acts.
- **One transaction**, so a bad location leaves no part behind with no stock (verified: a quantity
  with no/unknown location rolls the part back). Stock goes through `StockMovementService.apply`
  like every other on-hand change, writing the `INITIAL` movement, and the location is remembered
  as the user's last-used one — which is why the SPA calls `useAuth().refresh()` after a create
  that carried stock, exactly as Quick Add does.
- The dialog **pre-selects the last-used location** but leaves the amount blank, so the common
  "just catalogue it" case is still one field shorter than before.

## Stock Thresholds

- Minimum stock levels live in a **separate** `part_stock_threshold` table (V24) — one row per
  (part, root location). `stock_entry` no longer has a `minimum_quantity` column.
- A threshold is set **per root location only** (locations with `parent_id IS NULL`). "Low stock"
  means the SUM of all `stock_entry.quantity` rows for the part across the entire subtree of that
  root location is below the threshold. This covers stock spread across Building A > Room B > Shelf C
  with a single threshold on Building A.
- **`StockThresholdRepository`** uses native SQL with a recursive CTE to compute subtree totals —
  JPQL cannot express recursive CTEs so all threshold queries are native. Results map via the
  `StockThresholdView` Spring Data projection interface (getters matching column aliases).
- **`StockThresholdService`**: `findAll(partId?)`, `findLowStock()`, `countLowStock()`,
  `upsert(request)` (validates root-location constraint, returns 400 if not root), `delete(id)`.
- **Dashboard** `lowStockCount` is driven by `StockThresholdService.countLowStock()`.
- **Frontend**: the Part Detail page has a "Stock Thresholds" card showing each threshold row
  (root location, on-hand total across subtree, minimum, low-stock badge) with Add/Edit/Delete.
  The root-location picker filters `allLocations` to entries without a `parentId`. The Low Stock
  page (`pages/LowStock.tsx`) calls `getLowStockThresholds()` and shows root-location names and
  deficits.

## Locations

- Locations are **organisation-owned** (`location.organisation_id`, V36 — `owner_id` was dropped)
  **and hierarchical** (`location.parent_id`, self-FK, V21) — mirroring the Category tree pattern.
  Every member of an organisation shares its locations and may add/take/move stock in any of them;
  `StockMovementService.requireCurrentOrganisation` replaced the old own-location guard, and
  `applyNoOwnershipCheck` (the cross-user escape hatch for merge/move destinations) is gone. A part can be stored at any level (Building A,
  or Room B inside it, or Cupboard C inside that); `stock_entry`/`stock_movement` just reference a
  `location_id` regardless of depth.
- **Invariant**: a child shares its parent's organisation. `LocationService.resolveParent` resolves
  the parent through `findByIdAndOrganisationId`, so a cross-organisation parent is simply not found.
  Self-parenting and cycles are rejected (`isDescendant` walks the parent chain). `delete` refuses a
  location with sub-locations.
- **Sibling-name uniqueness**: an organisation may not have two locations with the same name under the same
  parent (`LocationRepository.existsSibling`, null-safe parent match for the root level). Names *may*
  repeat across different parents (two "Cupboard C"s in different rooms are fine — the breadcrumb
  disambiguates).
- `LocationDTO` carries `parentId`/`parentName`/`breadcrumb` ("Building A > Room B > Cupboard C", built
  by walking the parent chain). `GET /api/locations/tree` returns the nested `LocationTreeDTO` forest
  for the current organisation. The **Locations page** renders the tree (expand/collapse, per-node
  "+ Sub"/Edit/Delete gated by `canManage`, now simply `PARTS_EDIT`-or-admin, and per-node stock
  figures — parts / on-hand / value — from `LocationRepository.locationStats`, rolled up over each
  node's subtree so a collapsed parent accounts for everything below it; the tooltip splits out what
  is held directly at the node) with a hierarchical
  parent `<select>` over the organisation's locations minus the edited subtree. Stock-add pickers (Quick Add, Part Detail) show `breadcrumb`
  instead of the bare name.
- **Breadcrumb everywhere a location is shown**: `Location.breadcrumb()` (entity method, walks the
  parent chain) is the single source. `StockEntryDTO`/`StockMovementDTO` carry both `locationName`
  (leaf) and `locationBreadcrumb` (full path); the Part Detail stock + movement tables, the Low Stock
  table, and the "Remove stock at …" confirm all render the breadcrumb (falling back to the leaf).
- **Merge into** (`POST /api/locations/{id}/merge` `{targetId}` → `LocationService.merge`): folds a
  location into another, then deletes the source. Both must be in the current organisation.
  **History is preserved**: each source
  `stock_entry`'s on-hand qty is folded into the target's aggregate (find-or-create, carrying price),
  and the source's whole ledger is **re-pointed** to the target
  (`StockMovementRepository.repointLocation`) so every movement keeps its original type, price, date
  and author under the target location — no new movements are written (that would double-count the
  re-pointed history). The re-point also frees the source of `stock_movement` references (the
  `location` FK has no cascade); the now-empty source `stock_entry` rows are dropped and the location
  deleted. Folding-the-aggregate + re-pointing-the-ledger keeps the invariant `Σ(target movements) ==
  target on-hand` per part. Rejects self-merge and a source with sub-locations. The Locations page
  exposes a per-node "Merge into" action (gated by `canManage`) opening a modal that picks any
  location as the target.
- **Last-used location** (replaces the old "default location", V22): `app_user.last_location_id` (FK,
  `ON DELETE SET NULL`) records the location a user most recently added stock to. It is **not** a
  managed account field — `CurrentUserService.rememberLastLocation(location)` updates it inside the
  add transaction from both add paths (`QuickAddService.quickAdd`, `StockEntryService.create`).
  `UserDTO`/`AuthUser` expose `lastLocationId`/`lastLocationName` (breadcrumb); the Quick Add and Part
  Detail location pickers pre-select it (and require a location — submit is disabled otherwise).
  Because the pointer is remembered across organisations, `UserService.toCurrentUserDTO` blanks it
  when the location belongs to a different one. Since V36 a new user is **not** seeded with a
  starting location (locations are organisation-owned, so a personal one is meaningless) —
  `UserRequest` takes `organisationIds` instead. Deleting a location nulls it from any user that
  last used it.
