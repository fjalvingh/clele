import { useEffect, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import {
  cancelPurchaseOrder,
  closePurchaseOrder,
  deletePurchaseOrder,
  getLocations,
  getPurchaseOrder,
  placePurchaseOrder,
  previewOrderFile,
  purchaseOrderExportUrl,
  receivePurchaseOrder,
  removeItemFromOrder,
  removeOrderLine,
  reopenPurchaseOrder,
  replaceOrderLines,
  updateOrderLine,
  updatePurchaseOrder,
} from '../api';
import {
  type Location,
  type OrderFileColumn,
  type OrderFileMapping,
  type OrderFilePreview,
  type PurchaseOrder,
  type PurchaseOrderLine,
} from '../api/types';
import { useAuth } from '../auth/AuthContext';
import Modal from '../components/Modal';
import NumberInput from '../components/NumberInput';
import PartSearchPicker from '../components/PartSearchPicker';
import { useSettings } from '../settings/SettingsContext';
import { locationOptionLabel } from '../utils/locations';
import { ORDER_STATUS_COLORS, ORDER_STATUS_LABELS } from '../utils/orderStatus';

const inputClass =
  'mt-1 w-full rounded-lg border border-gray-300 px-3 py-2 text-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500';
const smallInputClass =
  'w-24 rounded border border-gray-300 px-2 py-1 text-sm text-right focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500';

const COLUMN_LABELS: Record<OrderFileColumn, string> = {
  CUSTOMER_PART_NUMBER: 'Our part number',
  SUPPLIER_PART_NUMBER: 'Supplier part number',
  MPN: 'Manufacturer part number',
  MANUFACTURER: 'Manufacturer',
  DESCRIPTION: 'Description',
  QUANTITY: 'Quantity',
  UNIT_PRICE: 'Unit price',
};

const MATCHED_BY_LABELS: Record<string, string> = {
  CUSTOMER_PART_NUMBER: 'our number',
  SUPPLIER_PART_NUMBER: 'supplier number',
  MPN: 'MPN',
  CATALOGUE: 'catalogue',
};

type Confirm = 'close' | 'cancel' | 'delete' | 'reopen';

/** One row of the supplier file as the user is deciding about it. */
interface ImportRowState {
  include: boolean;
  partId: number | null;
  partNumber: string | null;
  quantity: number | null;
}

/** One order line in the receive dialog. */
interface ReceiptState {
  include: boolean;
  quantity: number | null;
  locationId: number | null;
}

export default function PurchaseOrderDetailPage() {
  const { id } = useParams<{ id: string }>();
  const orderId = Number(id);
  const navigate = useNavigate();
  const { formatMoney } = useSettings();
  const { user, refresh } = useAuth();

  const [order, setOrder] = useState<PurchaseOrder | null>(null);
  const [locations, setLocations] = useState<Location[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const [editingOrder, setEditingOrder] = useState<{ supplierReference: string; notes: string } | null>(null);
  const [editingLine, setEditingLine] = useState<{
    line: PurchaseOrderLine; quantity: number | null; unitPrice: number | null; supplierPartNumber: string;
  } | null>(null);
  const [placing, setPlacing] = useState<string | null>(null);
  const [confirm, setConfirm] = useState<Confirm | null>(null);

  // Supplier file import (draft)
  const [importFile, setImportFile] = useState<File | null>(null);
  const [importPreview, setImportPreview] = useState<OrderFilePreview | null>(null);
  const [importRows, setImportRows] = useState<Record<number, ImportRowState>>({});
  const [pickingRow, setPickingRow] = useState<number | null>(null);
  const [showImport, setShowImport] = useState(false);

  // Receiving (ordered)
  const [showReceive, setShowReceive] = useState(false);
  const [receipts, setReceipts] = useState<Record<number, ReceiptState>>({});
  const [slipPreview, setSlipPreview] = useState<OrderFilePreview | null>(null);

  const load = () => {
    setLoading(true);
    getPurchaseOrder(orderId)
      .then(setOrder)
      .catch((e: Error) => setError(e.message))
      .finally(() => setLoading(false));
  };

  useEffect(load, [orderId]);
  useEffect(() => { getLocations().then(setLocations).catch(() => setLocations([])); }, []);

  const run = async (action: () => Promise<PurchaseOrder | void>, after?: () => void) => {
    setBusy(true);
    try {
      const updated = await action();
      if (updated) setOrder(updated);
      after?.();
    } catch (e) { setError((e as Error).message); }
    finally { setBusy(false); }
  };

  // ------------------------------------------------------------------
  // Supplier file
  // ------------------------------------------------------------------

  const readSupplierFile = async (file: File, mapping?: OrderFileMapping) => {
    setBusy(true);
    try {
      const preview = await previewOrderFile(orderId, 'order', file, mapping);
      setImportPreview(preview);
      const rows: Record<number, ImportRowState> = {};
      for (const r of preview.rows) {
        rows[r.rowNumber] = {
          include: r.partId != null,
          partId: r.partId ?? null,
          partNumber: r.partNumber ?? null,
          quantity: r.quantity ?? null,
        };
      }
      setImportRows(rows);
      setPickingRow(null);
    } catch (e) { setError((e as Error).message); }
    finally { setBusy(false); }
  };

  const changeMapping = (column: OrderFileColumn, header: string) => {
    if (!importFile || !importPreview) return;
    const mapping: OrderFileMapping = { ...importPreview.mapping, [column]: header || undefined };
    if (!header) delete mapping[column];
    readSupplierFile(importFile, mapping);
  };

  const includedImportRows = importPreview
    ? importPreview.rows.filter((r) => importRows[r.rowNumber]?.include && importRows[r.rowNumber]?.partId)
    : [];
  const importBlocked = includedImportRows.some((r) => !importRows[r.rowNumber]?.quantity);

  const applyImport = () => {
    if (!importPreview) return;
    const lines = includedImportRows.map((r) => ({
      partId: importRows[r.rowNumber].partId as number,
      quantity: importRows[r.rowNumber].quantity as number,
      unitPrice: r.unitPrice ?? null,
      supplierPartNumber: r.supplierPartNumber ?? null,
      mpn: r.mpn ?? null,
      manufacturer: r.manufacturer ?? null,
    }));
    run(() => replaceOrderLines(orderId, lines), () => {
      setShowImport(false);
      setNotice(`The order now has the supplier's ${lines.length} line${lines.length === 1 ? '' : 's'}.`);
    });
  };

  // ------------------------------------------------------------------
  // Receiving
  // ------------------------------------------------------------------

  const defaultLocation = (line: PurchaseOrderLine) =>
    line.suggestedLocationId ?? user?.lastLocationId ?? locations[0]?.id ?? null;

  /** Opens the receive dialog; `only` ticks that one line, otherwise nothing is ticked yet. */
  const openReceive = (only?: PurchaseOrderLine) => {
    const state: Record<number, ReceiptState> = {};
    for (const line of order?.lines ?? []) {
      if (line.outstanding <= 0) continue;
      state[line.id] = {
        include: only ? line.id === only.id : false,
        quantity: line.outstanding,
        locationId: defaultLocation(line),
      };
    }
    setReceipts(state);
    setSlipPreview(null);
    setShowReceive(true);
  };

  /** Fills the receive dialog from a delivery slip: every matched line ticked at the shipped quantity. */
  const readSlip = async (file: File) => {
    setBusy(true);
    try {
      const preview = await previewOrderFile(orderId, 'delivery', file);
      setSlipPreview(preview);
      const shipped: Record<number, number> = {};
      for (const r of preview.rows) {
        if (r.lineId != null && r.quantity != null) shipped[r.lineId] = (shipped[r.lineId] ?? 0) + r.quantity;
      }
      setReceipts((prev) => {
        const next = { ...prev };
        for (const [lineId, qty] of Object.entries(shipped)) {
          const line = order?.lines?.find((l) => l.id === Number(lineId));
          if (!line || !next[line.id]) continue;
          next[line.id] = { ...next[line.id], include: true, quantity: Math.min(qty, line.outstanding) };
        }
        return next;
      });
    } catch (e) { setError((e as Error).message); }
    finally { setBusy(false); }
  };

  const ticked = Object.entries(receipts).filter(([, r]) => r.include);
  const receiveBlocked = ticked.length === 0
    || ticked.some(([lineId, r]) => {
      const line = order?.lines?.find((l) => l.id === Number(lineId));
      return !r.quantity || !r.locationId || !line || r.quantity > line.outstanding;
    });

  const submitReceive = () => {
    const list = ticked.map(([lineId, r]) => ({
      lineId: Number(lineId),
      quantity: r.quantity as number,
      locationId: r.locationId as number,
    }));
    run(() => receivePurchaseOrder(orderId, list), () => {
      setShowReceive(false);
      refresh();
    });
  };

  // ------------------------------------------------------------------
  // Render
  // ------------------------------------------------------------------

  if (loading && !order) return <div className="p-8 text-gray-400 text-sm">Loading…</div>;
  if (!order) return <div className="p-8 text-red-600">{error ?? 'Order not found'}</div>;

  const lines = order.lines ?? [];
  const isDraft = order.status === 'DRAFT';
  const isOrdered = order.status === 'ORDERED';
  const anyReceived = lines.some((l) => l.qtyReceived > 0);
  const outstandingLines = lines.filter((l) => l.outstanding > 0);
  const unpriced = lines.filter((l) => l.unitPrice == null).length;

  return (
    <div className="p-4 md:p-8 max-w-6xl mx-auto space-y-6">
      <div className="text-sm text-gray-500">
        <Link to="/orders?tab=orders" className="hover:underline">Orders</Link>
        <span className="mx-1.5">›</span>
        <span className="text-gray-700">Order #{order.id}</span>
      </div>

      {error && (
        <div className="rounded-lg border border-red-200 bg-red-500/10 px-4 py-3 text-sm text-red-700">
          {error}
          <button className="ml-2 underline" onClick={() => setError(null)}>Dismiss</button>
        </div>
      )}
      {notice && (
        <div className="rounded-lg border border-green-200 bg-green-500/10 px-4 py-3 text-sm text-green-800">
          {notice}
          <button className="ml-2 underline" onClick={() => setNotice(null)}>Dismiss</button>
        </div>
      )}

      {/* Header */}
      <div className="rounded-lg border border-gray-200 bg-surface p-6 shadow-sm">
        <div className="flex flex-wrap items-start justify-between gap-4">
          <div className="min-w-0">
            <div className="flex items-center gap-3">
              <h1 className="text-xl font-bold text-gray-900">Order #{order.id} · {order.supplierName}</h1>
              <span className={`inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-medium ${ORDER_STATUS_COLORS[order.status]}`}>
                {ORDER_STATUS_LABELS[order.status]}
              </span>
            </div>
            <p className="mt-2 text-sm text-gray-600">
              {order.lineCount} {order.lineCount === 1 ? 'line' : 'lines'} ·{' '}
              <span className="font-medium">{formatMoney(order.total)}</span>
              {unpriced > 0 && <span className="text-amber-700"> · {unpriced} without a price</span>}
              {order.supplierReference && <> · ref <span className="font-medium">{order.supplierReference}</span></>}
            </p>
            <p className="mt-1 text-xs text-gray-400">
              Created {new Date(order.createdAt).toLocaleDateString()}
              {order.createdBy && ` by ${order.createdBy}`}
              {order.orderedAt && ` · ordered ${new Date(order.orderedAt).toLocaleDateString()}`}
              {order.receivedAt && ` · received ${new Date(order.receivedAt).toLocaleDateString()}`}
            </p>
            {order.notes && <p className="mt-2 text-sm text-gray-500 whitespace-pre-line">{order.notes}</p>}
          </div>
          <div className="flex flex-wrap items-center gap-2">
            <button
              onClick={() => setEditingOrder({ supplierReference: order.supplierReference ?? '', notes: order.notes ?? '' })}
              className="rounded-lg border border-gray-300 px-3 py-1.5 text-sm font-medium text-gray-700 hover:bg-gray-50"
            >
              Edit
            </button>
            {isDraft && (
              <>
                <a
                  href={purchaseOrderExportUrl(order.id)}
                  className="rounded-lg border border-gray-300 px-3 py-1.5 text-sm font-medium text-gray-700 hover:bg-gray-50"
                >
                  Download order file
                </a>
                <button
                  onClick={() => { setImportFile(null); setImportPreview(null); setShowImport(true); }}
                  className="rounded-lg border border-gray-300 px-3 py-1.5 text-sm font-medium text-gray-700 hover:bg-gray-50"
                >
                  Upload supplier file
                </button>
                <button
                  onClick={() => setPlacing(order.supplierReference ?? '')}
                  disabled={lines.length === 0}
                  className="rounded-lg bg-green-600 px-3 py-1.5 text-sm font-medium text-white hover:bg-green-700 disabled:opacity-50"
                >
                  Mark as ordered
                </button>
                <button
                  onClick={() => setConfirm('delete')}
                  className="rounded-lg border border-red-200 px-3 py-1.5 text-sm font-medium text-red-600 hover:bg-red-500/10"
                >
                  Delete
                </button>
              </>
            )}
            {isOrdered && (
              <>
                <button
                  onClick={() => openReceive()}
                  className="rounded-lg bg-green-600 px-3 py-1.5 text-sm font-medium text-white hover:bg-green-700"
                >
                  Receive…
                </button>
                {anyReceived ? (
                  <button
                    onClick={() => setConfirm('close')}
                    className="rounded-lg border border-gray-300 px-3 py-1.5 text-sm font-medium text-gray-700 hover:bg-gray-50"
                  >
                    Close order
                  </button>
                ) : (
                  <>
                    <button
                      onClick={() => setConfirm('reopen')}
                      className="rounded-lg border border-gray-300 px-3 py-1.5 text-sm font-medium text-gray-700 hover:bg-gray-50"
                    >
                      Back to draft
                    </button>
                    <button
                      onClick={() => setConfirm('cancel')}
                      className="rounded-lg border border-red-200 px-3 py-1.5 text-sm font-medium text-red-600 hover:bg-red-500/10"
                    >
                      Cancel order
                    </button>
                  </>
                )}
              </>
            )}
            {order.status === 'CANCELLED' && (
              <button
                onClick={() => setConfirm('delete')}
                className="rounded-lg border border-red-200 px-3 py-1.5 text-sm font-medium text-red-600 hover:bg-red-500/10"
              >
                Delete
              </button>
            )}
          </div>
        </div>
        {isDraft && (
          <ol className="mt-4 list-decimal list-inside space-y-0.5 text-xs text-gray-500">
            <li>Download the order file and upload it to {order.supplierName} (Mouser: BOM tool → upload).</li>
            <li>Export the matched result from {order.supplierName} as Excel and upload it here with "Upload supplier file" — it replaces the lines with what will really be ordered. Repeat as often as needed.</li>
            <li>Place the order at {order.supplierName}, then "Mark as ordered" with their order number.</li>
          </ol>
        )}
      </div>

      {/* Lines */}
      <div className="rounded-lg border border-gray-200 bg-surface shadow-sm">
        {lines.length === 0 ? (
          <div className="px-6 py-8 text-center text-sm text-gray-400">
            No lines. Put items from the <Link to="/orders" className="text-blue-600 hover:underline">order list</Link> on this order.
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="min-w-full divide-y divide-gray-100">
              <thead className="bg-gray-50">
                <tr>
                  <th className="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">Part</th>
                  <th className="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">{order.supplierName} no.</th>
                  <th className="px-4 py-3 text-right text-xs font-medium text-gray-500 uppercase tracking-wider">Qty</th>
                  <th className="px-4 py-3 text-right text-xs font-medium text-gray-500 uppercase tracking-wider">Unit price</th>
                  <th className="px-4 py-3 text-right text-xs font-medium text-gray-500 uppercase tracking-wider">Total</th>
                  {!isDraft && (
                    <th className="px-4 py-3 text-right text-xs font-medium text-gray-500 uppercase tracking-wider">Received</th>
                  )}
                  {(isDraft || isOrdered) && <th className="px-4 py-3" />}
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-50">
                {lines.map((line) => (
                  <tr key={line.id} className={isOrdered && line.outstanding === 0 ? 'bg-green-500/5' : 'hover:bg-gray-50'}>
                    <td className="px-4 py-3 align-top">
                      <Link to={`/parts/${line.partId}`} className="font-medium text-blue-600 hover:underline text-sm">
                        {line.partNumber}
                      </Link>
                      {line.description && <div className="text-xs text-gray-400 max-w-md truncate">{line.description}</div>}
                      {line.mpn && line.mpn !== line.partNumber && (
                        <div className="text-xs text-gray-400">{line.manufacturer ? `${line.manufacturer} ` : ''}{line.mpn}</div>
                      )}
                      {line.items.length > 0 && (
                        <div className="mt-1 flex flex-wrap gap-1">
                          {line.items.map((item) => (
                            <span key={item.id} className="inline-flex items-center gap-1 rounded-full bg-gray-500/10 px-2 py-0.5 text-xs text-gray-600">
                              {item.quantity} for{' '}
                              {item.projectId
                                ? <Link to={`/projects/${item.projectId}`} className="text-blue-600 hover:underline">{item.projectName}</Link>
                                : 'stock'}
                              {isDraft && (
                                <button
                                  onClick={() => run(() => removeItemFromOrder(order.id, item.id))}
                                  className="ml-0.5 text-gray-400 hover:text-red-500"
                                  title="Back to the order list"
                                  aria-label="Back to the order list"
                                >
                                  <svg className="h-3 w-3" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round">
                                    <path d="M6 6l12 12M18 6 6 18" />
                                  </svg>
                                </button>
                              )}
                            </span>
                          ))}
                        </div>
                      )}
                    </td>
                    <td className="px-4 py-3 align-top text-sm text-gray-700 whitespace-nowrap">{line.supplierPartNumber ?? <span className="text-gray-400">—</span>}</td>
                    <td className="px-4 py-3 align-top text-right text-sm text-gray-900 whitespace-nowrap">
                      {line.quantity}
                      {line.requested > 0 && line.requested !== line.quantity && (
                        <div className="text-xs text-gray-400">asked {line.requested}</div>
                      )}
                    </td>
                    <td className="px-4 py-3 align-top text-right text-sm text-gray-700 whitespace-nowrap">
                      {line.unitPrice != null ? formatMoney(line.unitPrice, { precise: true }) : <span className="text-gray-400">—</span>}
                    </td>
                    <td className="px-4 py-3 align-top text-right text-sm font-medium text-gray-900 whitespace-nowrap">
                      {line.totalPrice != null ? formatMoney(line.totalPrice) : <span className="text-gray-400">—</span>}
                    </td>
                    {!isDraft && (
                      <td className="px-4 py-3 align-top text-right text-sm whitespace-nowrap">
                        <span className={line.outstanding === 0 ? 'text-green-700 font-medium' : 'text-gray-700'}>
                          {line.qtyReceived} / {line.quantity}
                        </span>
                      </td>
                    )}
                    {isDraft && (
                      <td className="px-4 py-3 align-top text-right">
                        <div className="flex items-center justify-end gap-1">
                          <button
                            onClick={() => setEditingLine({
                              line,
                              quantity: line.quantity,
                              unitPrice: line.unitPrice ?? null,
                              supplierPartNumber: line.supplierPartNumber ?? '',
                            })}
                            className="rounded px-2 py-1 text-xs text-blue-600 hover:bg-blue-500/10"
                          >
                            Edit
                          </button>
                          <button
                            onClick={() => run(() => removeOrderLine(order.id, line.id))}
                            className="rounded px-2 py-1 text-xs text-red-500 hover:bg-red-500/10"
                          >
                            Remove
                          </button>
                        </div>
                      </td>
                    )}
                    {isOrdered && (
                      <td className="px-4 py-3 align-top text-right">
                        {line.outstanding > 0 && (
                          <button
                            onClick={() => openReceive(line)}
                            className="rounded px-2 py-1 text-xs text-green-700 hover:bg-green-500/10"
                          >
                            Receive
                          </button>
                        )}
                      </td>
                    )}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {/* Edit order */}
      <Modal open={editingOrder !== null} onClose={() => setEditingOrder(null)} title={`Edit order #${order.id}`}>
        {editingOrder && (
          <div className="space-y-4">
            <div>
              <label className="block text-sm font-medium text-gray-700">{order.supplierName} order number</label>
              <input
                className={inputClass}
                value={editingOrder.supplierReference}
                onChange={(e) => setEditingOrder({ ...editingOrder, supplierReference: e.target.value })}
              />
            </div>
            <div>
              <label className="block text-sm font-medium text-gray-700">Notes</label>
              <textarea
                className={inputClass}
                rows={3}
                value={editingOrder.notes}
                onChange={(e) => setEditingOrder({ ...editingOrder, notes: e.target.value })}
              />
            </div>
            <div className="flex justify-end gap-2 pt-2">
              <button onClick={() => setEditingOrder(null)} className="rounded-lg border border-gray-300 px-4 py-2 text-sm font-medium hover:bg-gray-50">
                Cancel
              </button>
              <button
                onClick={() => run(() => updatePurchaseOrder(order.id, editingOrder), () => setEditingOrder(null))}
                disabled={busy}
                className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-50"
              >
                Save
              </button>
            </div>
          </div>
        )}
      </Modal>

      {/* Edit line */}
      <Modal open={editingLine !== null} onClose={() => setEditingLine(null)} title={`Edit ${editingLine?.line.partNumber ?? ''}`}>
        {editingLine && (
          <div className="space-y-4">
            <div className="grid grid-cols-2 gap-4">
              <div>
                <label className="block text-sm font-medium text-gray-700">Quantity</label>
                <NumberInput
                  className={inputClass}
                  value={editingLine.quantity}
                  onChange={(v) => setEditingLine({ ...editingLine, quantity: v })}
                />
              </div>
              <div>
                <label className="block text-sm font-medium text-gray-700">Unit price</label>
                <NumberInput
                  decimal
                  className={inputClass}
                  value={editingLine.unitPrice}
                  onChange={(v) => setEditingLine({ ...editingLine, unitPrice: v })}
                />
              </div>
            </div>
            <div>
              <label className="block text-sm font-medium text-gray-700">{order.supplierName} part number</label>
              <input
                className={inputClass}
                value={editingLine.supplierPartNumber}
                onChange={(e) => setEditingLine({ ...editingLine, supplierPartNumber: e.target.value })}
              />
            </div>
            <div className="flex justify-end gap-2 pt-2">
              <button onClick={() => setEditingLine(null)} className="rounded-lg border border-gray-300 px-4 py-2 text-sm font-medium hover:bg-gray-50">
                Cancel
              </button>
              <button
                onClick={() => run(() => updateOrderLine(order.id, editingLine.line.id, {
                  partId: editingLine.line.partId,
                  quantity: editingLine.quantity ?? 0,
                  unitPrice: editingLine.unitPrice,
                  supplierPartNumber: editingLine.supplierPartNumber || null,
                }), () => setEditingLine(null))}
                disabled={busy || !editingLine.quantity}
                className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-50"
              >
                Save
              </button>
            </div>
          </div>
        )}
      </Modal>

      {/* Mark as ordered */}
      <Modal open={placing !== null} onClose={() => setPlacing(null)} title="Mark as ordered">
        {placing !== null && (
          <div className="space-y-4">
            <p className="text-sm text-gray-600">
              The order has been placed at {order.supplierName}. Its lines are fixed from here on, and
              deliveries can be received against it.
            </p>
            <div>
              <label className="block text-sm font-medium text-gray-700">{order.supplierName} order number</label>
              <input className={inputClass} value={placing} onChange={(e) => setPlacing(e.target.value)} autoFocus />
            </div>
            <div className="flex justify-end gap-2 pt-2">
              <button onClick={() => setPlacing(null)} className="rounded-lg border border-gray-300 px-4 py-2 text-sm font-medium hover:bg-gray-50">
                Cancel
              </button>
              <button
                onClick={() => run(() => placePurchaseOrder(order.id, placing || null), () => setPlacing(null))}
                disabled={busy}
                className="rounded-lg bg-green-600 px-4 py-2 text-sm font-medium text-white hover:bg-green-700 disabled:opacity-50"
              >
                Mark as ordered
              </button>
            </div>
          </div>
        )}
      </Modal>

      {/* Confirmations */}
      <Modal
        open={confirm !== null}
        onClose={() => setConfirm(null)}
        title={confirm === 'close' ? 'Close order' : confirm === 'cancel' ? 'Cancel order' : confirm === 'reopen' ? 'Back to draft' : 'Delete order'}
      >
        <div className="space-y-4">
          <p className="text-sm text-gray-600">
            {confirm === 'close' && (
              <>The rest will not be delivered. {outstandingLines.length > 0
                ? `What is still outstanding on ${outstandingLines.length} line${outstandingLines.length === 1 ? '' : 's'} goes back on the order list.`
                : 'Nothing is outstanding.'}</>
            )}
            {confirm === 'cancel' && 'The order will not be placed after all. Its items go back on the order list.'}
            {confirm === 'reopen' && 'The order was not placed yet after all: it becomes a draft again and its lines can be changed.'}
            {confirm === 'delete' && (isDraft
              ? 'Delete this draft? Its items go back on the order list.'
              : 'Delete this cancelled order for good?')}
          </p>
          <div className="flex justify-end gap-2 pt-2">
            <button onClick={() => setConfirm(null)} className="rounded-lg border border-gray-300 px-4 py-2 text-sm font-medium hover:bg-gray-50">
              Keep it
            </button>
            <button
              onClick={() => {
                if (confirm === 'delete') {
                  run(() => deletePurchaseOrder(order.id).then(() => undefined), () => navigate('/orders?tab=orders'));
                } else {
                  const action = confirm === 'close' ? closePurchaseOrder : confirm === 'cancel' ? cancelPurchaseOrder : reopenPurchaseOrder;
                  run(() => action(order.id), () => setConfirm(null));
                }
              }}
              disabled={busy}
              className={`rounded-lg px-4 py-2 text-sm font-medium text-white disabled:opacity-50 ${
                confirm === 'delete' || confirm === 'cancel' ? 'bg-red-600 hover:bg-red-700' : 'bg-blue-600 hover:bg-blue-700'
              }`}
            >
              {confirm === 'close' ? 'Close order' : confirm === 'cancel' ? 'Cancel order' : confirm === 'reopen' ? 'Back to draft' : 'Delete'}
            </button>
          </div>
        </div>
      </Modal>

      {/* Supplier file */}
      <Modal open={showImport} onClose={() => setShowImport(false)} title={`Upload ${order.supplierName}'s file`} wide>
        <div className="space-y-4">
          <p className="text-sm text-gray-600">
            The Excel (or CSV) {order.supplierName} gives back after matching the order file. Its rows
            replace this order's lines: quantities, prices and their part numbers. Nothing changes until
            you press "Replace lines".
          </p>
          <input
            type="file"
            accept=".xlsx,.xls,.csv,.txt"
            onChange={(e) => {
              const file = e.target.files?.[0] ?? null;
              setImportFile(file);
              if (file) readSupplierFile(file);
            }}
            className="block text-sm"
          />
          {busy && <p className="text-xs text-gray-400">Reading…</p>}

          {importPreview && (
            <>
              <details className="rounded-lg border border-gray-200 px-3 py-2">
                <summary className="cursor-pointer text-sm text-gray-700">Columns</summary>
                <div className="mt-2 grid grid-cols-1 sm:grid-cols-2 gap-2">
                  {(Object.keys(COLUMN_LABELS) as OrderFileColumn[]).map((column) => (
                    <label key={column} className="flex items-center justify-between gap-2 text-xs text-gray-600">
                      {COLUMN_LABELS[column]}
                      <select
                        className="rounded border border-gray-300 px-1 py-0.5 text-xs"
                        value={importPreview.mapping[column] ?? ''}
                        onChange={(e) => changeMapping(column, e.target.value)}
                      >
                        <option value="">—</option>
                        {importPreview.headers.map((h) => <option key={h} value={h}>{h}</option>)}
                      </select>
                    </label>
                  ))}
                </div>
              </details>

              <div className="max-h-96 overflow-auto rounded-lg border border-gray-200">
                <table className="min-w-full divide-y divide-gray-100 text-sm">
                  <thead className="bg-gray-50 sticky top-0">
                    <tr>
                      <th className="px-2 py-2 w-8" />
                      <th className="px-2 py-2 text-left text-xs font-medium text-gray-500">In the file</th>
                      <th className="px-2 py-2 text-right text-xs font-medium text-gray-500">Qty</th>
                      <th className="px-2 py-2 text-right text-xs font-medium text-gray-500">Price</th>
                      <th className="px-2 py-2 text-left text-xs font-medium text-gray-500">Part</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-gray-50">
                    {importPreview.rows.map((r) => {
                      const state = importRows[r.rowNumber];
                      return (
                        <tr key={r.rowNumber} className={state?.include ? '' : 'opacity-60'}>
                          <td className="px-2 py-2 align-top">
                            <input
                              type="checkbox"
                              checked={!!state?.include}
                              disabled={!state?.partId}
                              onChange={() => setImportRows((prev) => ({
                                ...prev, [r.rowNumber]: { ...prev[r.rowNumber], include: !prev[r.rowNumber].include },
                              }))}
                              aria-label={`Use row ${r.rowNumber}`}
                            />
                          </td>
                          <td className="px-2 py-2 align-top">
                            <div className="text-gray-900">{r.supplierPartNumber ?? r.mpn ?? r.customerPartNumber}</div>
                            <div className="text-xs text-gray-400">
                              row {r.rowNumber}
                              {r.mpn && ` · ${r.manufacturer ? `${r.manufacturer} ` : ''}${r.mpn}`}
                              {r.customerPartNumber && ` · ours ${r.customerPartNumber}`}
                            </div>
                            {r.warning && <div className="text-xs text-amber-700">{r.warning}</div>}
                          </td>
                          <td className="px-2 py-2 align-top text-right">
                            <NumberInput
                              className={smallInputClass}
                              value={state?.quantity ?? null}
                              onChange={(v) => setImportRows((prev) => ({ ...prev, [r.rowNumber]: { ...prev[r.rowNumber], quantity: v } }))}
                            />
                          </td>
                          <td className="px-2 py-2 align-top text-right whitespace-nowrap text-gray-700">
                            {r.unitPrice != null ? formatMoney(r.unitPrice, { precise: true }) : '—'}
                          </td>
                          <td className="px-2 py-2 align-top">
                            {state?.partId ? (
                              <div>
                                <span className="font-medium text-gray-900">{state.partNumber}</span>
                                {r.matchedBy && r.partId === state.partId && (
                                  <span className="ml-1 text-xs text-gray-400">by {MATCHED_BY_LABELS[r.matchedBy]}</span>
                                )}
                                <button onClick={() => setPickingRow(r.rowNumber)} className="ml-2 text-xs text-blue-600 hover:underline">
                                  change
                                </button>
                              </div>
                            ) : pickingRow !== r.rowNumber && (
                              <button onClick={() => setPickingRow(r.rowNumber)} className="text-xs text-blue-600 hover:underline">
                                Not matched — pick a part
                              </button>
                            )}
                            {pickingRow === r.rowNumber && (
                              <div className="mt-1 w-72">
                                <PartSearchPicker
                                  autoFocus
                                  initialQuery={r.mpn ?? r.customerPartNumber ?? ''}
                                  onSelect={(p) => {
                                    setImportRows((prev) => ({
                                      ...prev,
                                      [r.rowNumber]: { ...prev[r.rowNumber], partId: p.id, partNumber: p.partNumber, include: true },
                                    }));
                                    setPickingRow(null);
                                  }}
                                />
                              </div>
                            )}
                          </td>
                        </tr>
                      );
                    })}
                    {importPreview.rows.length === 0 && (
                      <tr><td colSpan={5} className="px-2 py-4 text-center text-gray-400">No rows found. Check the columns above.</td></tr>
                    )}
                  </tbody>
                </table>
              </div>

              {importPreview.missingLines.length > 0 && (
                <div className="rounded-lg border border-amber-200 bg-amber-500/10 px-3 py-2 text-sm text-amber-800">
                  Not in the file, so removed from the order (their items go back on the order list):{' '}
                  {importPreview.missingLines.map((l) => `${l.quantity} × ${l.partNumber}`).join(', ')}
                </div>
              )}
            </>
          )}

          <div className="flex justify-end gap-2 pt-2">
            <button onClick={() => setShowImport(false)} className="rounded-lg border border-gray-300 px-4 py-2 text-sm font-medium hover:bg-gray-50">
              Cancel
            </button>
            <button
              onClick={applyImport}
              disabled={busy || includedImportRows.length === 0 || importBlocked}
              className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-50"
            >
              Replace lines ({includedImportRows.length})
            </button>
          </div>
          {importBlocked && <p className="text-right text-xs text-amber-700">Every ticked row needs a quantity.</p>}
        </div>
      </Modal>

      {/* Receive */}
      <Modal open={showReceive} onClose={() => setShowReceive(false)} title="Receive parts" wide>
        <div className="space-y-4">
          <p className="text-sm text-gray-600">
            Tick each line as it is unpacked, or fill the list from the delivery slip. The parts are
            booked into stock at the location chosen, at the order's price.
          </p>
          <label className="block text-sm text-gray-700">
            Delivery slip (Excel or CSV):{' '}
            <input
              type="file"
              accept=".xlsx,.xls,.csv,.txt"
              onChange={(e) => { const f = e.target.files?.[0]; if (f) readSlip(f); }}
              className="text-sm"
            />
          </label>
          {slipPreview && (() => {
            const notOnOrder = slipPreview.rows.filter((r) => r.lineId == null);
            const warned = slipPreview.rows.filter((r) => r.lineId != null && r.warning);
            return (notOnOrder.length > 0 || warned.length > 0) && (
              <div className="rounded-lg border border-amber-200 bg-amber-500/10 px-3 py-2 text-xs text-amber-800 space-y-1">
                {notOnOrder.length > 0 && (
                  <div>Not on this order: {notOnOrder.map((r) => r.supplierPartNumber ?? r.mpn ?? r.customerPartNumber).join(', ')}</div>
                )}
                {warned.map((r) => <div key={r.rowNumber}>{r.partNumber}: {r.warning}</div>)}
              </div>
            );
          })()}
          <div className="max-h-96 overflow-auto rounded-lg border border-gray-200">
            <table className="min-w-full divide-y divide-gray-100 text-sm">
              <thead className="bg-gray-50 sticky top-0">
                <tr>
                  <th className="px-2 py-2 w-8" />
                  <th className="px-2 py-2 text-left text-xs font-medium text-gray-500">Part</th>
                  <th className="px-2 py-2 text-right text-xs font-medium text-gray-500">To come</th>
                  <th className="px-2 py-2 text-right text-xs font-medium text-gray-500">Received now</th>
                  <th className="px-2 py-2 text-left text-xs font-medium text-gray-500">Location</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-50">
                {outstandingLines.map((line) => {
                  const r = receipts[line.id];
                  if (!r) return null;
                  const set = (patch: Partial<ReceiptState>) =>
                    setReceipts((prev) => ({ ...prev, [line.id]: { ...prev[line.id], ...patch } }));
                  return (
                    <tr key={line.id} className={r.include ? 'bg-green-500/5' : ''}>
                      <td className="px-2 py-2">
                        <input type="checkbox" checked={r.include} onChange={() => set({ include: !r.include })} aria-label={`Receive ${line.partNumber}`} />
                      </td>
                      <td className="px-2 py-2">
                        <div className="font-medium text-gray-900">{line.partNumber}</div>
                        {line.supplierPartNumber && <div className="text-xs text-gray-400">{line.supplierPartNumber}</div>}
                      </td>
                      <td className="px-2 py-2 text-right text-gray-700">{line.outstanding}</td>
                      <td className="px-2 py-2 text-right">
                        <NumberInput
                          className={smallInputClass}
                          value={r.quantity}
                          onChange={(v) => set({ quantity: v, include: true })}
                        />
                        {r.quantity != null && r.quantity > line.outstanding && (
                          <div className="text-xs text-red-600">at most {line.outstanding}</div>
                        )}
                      </td>
                      <td className="px-2 py-2">
                        <select
                          className="w-full max-w-xs rounded border border-gray-300 px-2 py-1 text-sm"
                          value={r.locationId ?? ''}
                          onChange={(e) => set({ locationId: e.target.value ? Number(e.target.value) : null })}
                        >
                          <option value="">— choose —</option>
                          {locations.map((l) => <option key={l.id} value={l.id}>{locationOptionLabel(l)}</option>)}
                        </select>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
          <div className="flex justify-end gap-2 pt-2">
            <button onClick={() => setShowReceive(false)} className="rounded-lg border border-gray-300 px-4 py-2 text-sm font-medium hover:bg-gray-50">
              Cancel
            </button>
            <button
              onClick={submitReceive}
              disabled={busy || receiveBlocked}
              className="rounded-lg bg-green-600 px-4 py-2 text-sm font-medium text-white hover:bg-green-700 disabled:opacity-50"
            >
              {busy ? 'Booking…' : `Receive ${ticked.length} line${ticked.length === 1 ? '' : 's'}`}
            </button>
          </div>
        </div>
      </Modal>
    </div>
  );
}
