# Ordering

Part of the Clele documentation — `CLAUDE.md` holds the overview and the index of these files;
`API.md` lists the REST endpoints. Package `com.clele.parts.service.order`; screens
`pages/Orders.tsx` (`/orders`, order list + order overview) and `pages/PurchaseOrderDetail.tsx`
(`/orders/:id`). Everything is `PARTS_EDIT` and shared per organisation, like locations.

## Three layers, from demand to delivery

| table | what it is |
|---|---|
| `order_item` | the **order list**: "we need N of this part", optionally for a project, with a price. On the list while `purchase_order_line_id` is NULL |
| `purchase_order` | one order at one supplier, `DRAFT → ORDERED → RECEIVED`, or `CANCELLED` |
| `purchase_order_line` | what is *actually* ordered: one row per (order, part). Items point at it |

Putting items on an order links each to the order's line for its part, **summing quantities** —
two items for one part (one for a project, one for stock) are one line at the supplier. The item
quantities stay the *demand* (`requested` on the line DTO); the line quantity is what the supplier
confirmed, which differs once a minimum order quantity or reel size has been applied. The project
link lives on the items, so it survives everything the supplier does to the lines.

A part on the order list is an existing catalogue part. A part known only by our own code
(`personal_number`) gets its manufacturer's MPN when the supplier's file comes back — see below.

`supplier` is a small per-organisation list (Mouser seeded by V63 for every organisation, copied
from the template for new ones). `part_supplier` remembers per (part, supplier) the supplier's own
part number and last price: a new line carries them from the start, and a new order list item
without a price takes the most recent `last_unit_price` at any supplier.

## The draft: iterating with the supplier

1. **Download order file** (`GET /purchase-orders/{id}/export`): an .xlsx, one row per line,
   columns `<Supplier> Part Number`, `Manufacturer Part Number`, `Manufacturer`,
   `Customer Part Number`, `Quantity`, `Description`. Every cell is text.
2. The user uploads it to the supplier (Mouser: BOM tool), which matches it.
3. **Upload supplier file**: the supplier's matched export is read *against the draft* and
   **stores nothing** (`POST /{id}/supplier-file` → `OrderFilePreviewDTO`). The SPA shows the rows,
   lets the user fix the column mapping, pick parts for unmatched rows or untick rows, and then sends
   the result as an ordinary `PUT /{id}/lines` (**replace lines**). No half-imported state exists,
   and corrections need no second upload. Repeat as often as needed.
4. **Mark as ordered** with the supplier's order number: the lines are fixed from here on.

⚠️ **Our part number travels out in the "Customer Part Number" column and is the primary match key
when the file comes back** — Mouser keeps it, as do most distributors' upload tools. It is ours, so
the match is exact even when the supplier changed everything else about the row. Then the
supplier's part number (line or `part_supplier`), then the MPN — each only when it hits exactly
one line. On a draft, a row that hits no line is looked up in the catalogue (exact part number or
MPN, unique only) and becomes a new line; anything else is left for the user to pick.

**Replace lines** (`PurchaseOrderService.replaceLines`): lines whose part is not in the new set are
removed and **their items go back on the order list**; the rest are created or overwritten
(quantity, price, supplier part number). Rows for the same part are summed at the quantity-weighted
price. What the supplier said is kept: `part_supplier` gets their number and price, the items get
the line price, and the part gets the **MPN and manufacturer only where it has none yet**. A part
that already has a different MPN keeps it — the preview shows a warning, it never decides it.

## Reading supplier files

`OrderFileReader` sniffs the content (zip → .xlsx, OLE2 → .xls, else delimited text via
`BomFileParser.readRecords`). Numeric Excel cells are read at **full value, not as displayed** — a
price column formatted to two decimals would otherwise turn € 0.0034 into € 0.00.
`OrderFileColumnMapper` finds the header row (the first of the top 30 that maps ≥ 2 columns, one
of which identifies a part — distributor exports carry title lines) and maps headers by normalised
synonyms, like the BOM import. "Price (EUR)" is matched by prefix; anything containing "ext"/"total"
is not a unit price. A delivery slip prefers a *shipped* quantity column over the ordered one.
Prices are read in either decimal convention (last separator wins; a lone comma is decimal).

## Receiving

Only an `ORDERED` order receives. `POST /{id}/receive` `{receipts: [{lineId, quantity, locationId}]}`
books each as a `PURCHASE` through `StockMovementService.applyForOrder` at the line's price, with
`stock_movement.purchase_order_id` set (Part Detail links the movement to the order). More than is
outstanding is refused. When nothing is outstanding the order becomes `RECEIVED`.

The **Receive** dialog is the one place for both ways the user works: tick lines one by one as they
are unpacked, or upload the delivery slip (`POST /{id}/delivery-file`, again preview only), which
ticks every matched line at the shipped quantity. Each line defaults to the location already
holding most of the part (`suggestedLocationId`), else the user's last-used location.

Received parts go **to stock only** — a line ordered for a project is not allocated to it; the
project fetches them like any other stock.

- **Close** (`ORDERED → RECEIVED`) ends an order that will not be delivered in full; what is still
  outstanding goes back on the order list as new items (for the line's project when all its items
  shared one).
- **Back to draft** and **Cancel** are allowed only while nothing has been received. Cancelling
  puts the items back on the list and keeps the lines as a record.
- **Delete**: drafts (items back on the list) and cancelled orders only.
- A part on any order line cannot be deleted (409); order list items go with their part (cascade).

## Prices

Order prices are `NUMERIC(14,5)`. V63 also widened `stock_entry`, `stock_movement` and the kit
generation price columns from `(10,2)` to `(14,5)` and `StockMovementService` now keeps the
weighted average at 5 decimals: before, receiving € 0.0034 resistors booked them at € 0.00.
`formatMoney(x, { precise: true })` shows up to 5 decimals for unit prices.
