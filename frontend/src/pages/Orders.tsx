import { useEffect, useMemo, useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import {
  addItemsToOrder,
  createOrderItem,
  createPurchaseOrder,
  createSupplier,
  deleteOrderItem,
  deleteSupplier,
  getOrderItems,
  getProjects,
  getPurchaseOrders,
  getSuppliers,
  renameSupplier,
  updateOrderItem,
} from '../api';
import {
  type OrderItem,
  type Project,
  type PurchaseOrder,
  type Supplier,
} from '../api/types';
import Modal from '../components/Modal';
import NumberInput from '../components/NumberInput';
import PartSearchPicker from '../components/PartSearchPicker';
import { ORDER_STATUS_COLORS, ORDER_STATUS_LABELS } from '../utils/orderStatus';
import { useSettings } from '../settings/SettingsContext';

// Quantity and price boxes may be empty while being retyped; coerced on submit.
interface ItemForm {
  partId: number;
  partNumber: string;
  quantity: number | null;
  unitPrice: number | null;
  projectId: number | null;
  notes: string;
}

const EMPTY_FORM: ItemForm = { partId: 0, partNumber: '', quantity: 1, unitPrice: null, projectId: null, notes: '' };

const inputClass =
  'mt-1 w-full rounded-lg border border-gray-300 px-3 py-2 text-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500';

type Tab = 'list' | 'orders';

export default function OrdersPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const tab: Tab = searchParams.get('tab') === 'orders' ? 'orders' : 'list';
  const navigate = useNavigate();
  const { formatMoney } = useSettings();

  const [items, setItems] = useState<OrderItem[]>([]);
  const [orders, setOrders] = useState<PurchaseOrder[]>([]);
  const [suppliers, setSuppliers] = useState<Supplier[]>([]);
  const [projects, setProjects] = useState<Project[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const [selected, setSelected] = useState<Set<number>>(new Set());

  // Add / edit item
  const [showItem, setShowItem] = useState(false);
  const [editingId, setEditingId] = useState<number | null>(null);
  const [form, setForm] = useState<ItemForm>(EMPTY_FORM);
  const [removing, setRemoving] = useState<OrderItem | null>(null);
  const [busy, setBusy] = useState(false);

  // Put selected items on an order: a new one at a supplier, or an existing draft.
  const [showPutOnOrder, setShowPutOnOrder] = useState(false);
  const [targetOrderId, setTargetOrderId] = useState<number | 'new'>('new');
  const [targetSupplierId, setTargetSupplierId] = useState<number | null>(null);

  // Supplier list management
  const [showSuppliers, setShowSuppliers] = useState(false);
  const [newSupplier, setNewSupplier] = useState('');
  const [renaming, setRenaming] = useState<{ id: number; name: string } | null>(null);

  const load = () => {
    setLoading(true);
    Promise.all([getOrderItems(), getPurchaseOrders(), getSuppliers()])
      .then(([i, o, s]) => {
        setItems(i);
        setOrders(o);
        setSuppliers(s);
        setSelected((prev) => new Set([...prev].filter((id) => i.some((item) => item.id === id))));
      })
      .catch((e: Error) => setError(e.message))
      .finally(() => setLoading(false));
  };

  useEffect(load, []);
  useEffect(() => { getProjects().then(setProjects).catch(() => setProjects([])); }, []);

  const drafts = orders.filter((o) => o.status === 'DRAFT');
  const listTotal = items.reduce((sum, i) => sum + (i.totalPrice ?? 0), 0);
  const unpriced = items.filter((i) => i.unitPrice == null).length;
  const selectedItems = items.filter((i) => selected.has(i.id));
  const selectedTotal = selectedItems.reduce((sum, i) => sum + (i.totalPrice ?? 0), 0);

  // The project picker offers the user's own active projects; an item may name another member's
  // project (the list is shared), which is kept as an extra option so editing does not drop it.
  const projectOptions = useMemo(() => {
    const own = projects.filter((p) => p.status === 'ACTIVE').map((p) => ({ id: p.id, name: p.name }));
    if (form.projectId && !own.some((p) => p.id === form.projectId)) {
      const item = items.find((i) => i.projectId === form.projectId);
      own.push({ id: form.projectId, name: item?.projectName ?? `Project ${form.projectId}` });
    }
    return own;
  }, [projects, form.projectId, items]);

  // ------------------------------------------------------------------
  // Actions
  // ------------------------------------------------------------------

  const openAdd = () => {
    setEditingId(null);
    setForm(EMPTY_FORM);
    setShowItem(true);
  };

  const openEdit = (item: OrderItem) => {
    setEditingId(item.id);
    setForm({
      partId: item.partId,
      partNumber: item.partNumber,
      quantity: item.quantity,
      unitPrice: item.unitPrice ?? null,
      projectId: item.projectId ?? null,
      notes: item.notes ?? '',
    });
    setShowItem(true);
  };

  const handleSaveItem = async () => {
    const quantity = form.quantity ?? 0;
    if (!form.partId || quantity < 1) return;
    setBusy(true);
    try {
      const request = {
        partId: form.partId,
        quantity,
        unitPrice: form.unitPrice,
        projectId: form.projectId,
        notes: form.notes || null,
      };
      if (editingId !== null) await updateOrderItem(editingId, request);
      else await createOrderItem(request);
      setShowItem(false);
      load();
    } catch (e) { setError((e as Error).message); }
    finally { setBusy(false); }
  };

  const handleRemove = async () => {
    if (!removing) return;
    setBusy(true);
    try {
      await deleteOrderItem(removing.id);
      setRemoving(null);
      load();
    } catch (e) { setError((e as Error).message); }
    finally { setBusy(false); }
  };

  const openPutOnOrder = () => {
    setTargetOrderId(drafts.length > 0 ? drafts[0].id : 'new');
    setTargetSupplierId(suppliers[0]?.id ?? null);
    setShowPutOnOrder(true);
  };

  const handlePutOnOrder = async () => {
    const itemIds = [...selected];
    if (itemIds.length === 0) return;
    setBusy(true);
    try {
      const order = targetOrderId === 'new'
        ? await createPurchaseOrder({ supplierId: targetSupplierId ?? undefined, itemIds })
        : await addItemsToOrder(targetOrderId, itemIds);
      navigate(`/orders/${order.id}`);
    } catch (e) { setError((e as Error).message); setBusy(false); }
  };

  const handleAddSupplier = async () => {
    if (!newSupplier.trim()) return;
    try {
      await createSupplier({ name: newSupplier.trim() });
      setNewSupplier('');
      setSuppliers(await getSuppliers());
    } catch (e) { setError((e as Error).message); }
  };

  const handleRenameSupplier = async () => {
    if (!renaming || !renaming.name.trim()) return;
    try {
      await renameSupplier(renaming.id, { name: renaming.name.trim() });
      setRenaming(null);
      setSuppliers(await getSuppliers());
    } catch (e) { setError((e as Error).message); }
  };

  const handleDeleteSupplier = async (id: number) => {
    try {
      await deleteSupplier(id);
      setSuppliers(await getSuppliers());
    } catch (e) { setError((e as Error).message); }
  };

  const toggle = (id: number) =>
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id); else next.add(id);
      return next;
    });

  const allSelected = items.length > 0 && selected.size === items.length;

  // ------------------------------------------------------------------
  // Render
  // ------------------------------------------------------------------

  const tabClass = (t: Tab) =>
    `px-4 py-2 text-sm font-medium border-b-2 -mb-px ${
      tab === t ? 'border-blue-600 text-blue-600' : 'border-transparent text-gray-500 hover:text-gray-700'
    }`;

  return (
    <div className="p-4 md:p-8 max-w-6xl mx-auto space-y-6">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h1 className="text-2xl font-bold text-gray-900">Orders</h1>
        <button
          onClick={() => setShowSuppliers(true)}
          className="rounded-lg border border-gray-300 px-3 py-1.5 text-sm font-medium text-gray-700 hover:bg-gray-50"
        >
          Suppliers
        </button>
      </div>

      {error && (
        <div className="rounded-lg border border-red-200 bg-red-500/10 px-4 py-3 text-sm text-red-700">
          {error}
          <button className="ml-2 underline" onClick={() => setError(null)}>Dismiss</button>
        </div>
      )}

      <div className="flex border-b border-gray-200">
        <button className={tabClass('list')} onClick={() => setSearchParams({})}>
          Order list{items.length > 0 && ` (${items.length})`}
        </button>
        <button className={tabClass('orders')} onClick={() => setSearchParams({ tab: 'orders' })}>
          Orders{orders.length > 0 && ` (${orders.length})`}
        </button>
      </div>

      {loading ? (
        <div className="text-gray-400 text-sm">Loading…</div>
      ) : tab === 'list' ? (
        <div className="rounded-lg border border-gray-200 bg-surface shadow-sm">
          <div className="flex flex-wrap items-center justify-between gap-3 border-b border-gray-100 px-6 py-4">
            <div>
              <h2 className="font-semibold text-gray-900">Parts to order</h2>
              <p className="mt-0.5 text-xs text-gray-500">
                {items.length} {items.length === 1 ? 'item' : 'items'} · {formatMoney(listTotal)}
                {unpriced > 0 && <> · {unpriced} without a price</>}
              </p>
            </div>
            <div className="flex items-center gap-2">
              {selected.size > 0 && (
                <button
                  onClick={openPutOnOrder}
                  className="rounded-lg bg-green-600 px-3 py-1.5 text-sm font-medium text-white hover:bg-green-700"
                >
                  Put {selected.size} on an order ({formatMoney(selectedTotal)})
                </button>
              )}
              <button
                onClick={openAdd}
                className="rounded-lg bg-blue-600 px-3 py-1.5 text-sm font-medium text-white hover:bg-blue-700"
              >
                Add Part
              </button>
            </div>
          </div>
          {items.length === 0 ? (
            <div className="px-6 py-8 text-center text-sm text-gray-400">
              Nothing to order. Use "Add Part" to put a part on the list.
            </div>
          ) : (
            <div className="overflow-x-auto">
              <table className="min-w-full divide-y divide-gray-100">
                <thead className="bg-gray-50">
                  <tr>
                    <th className="px-4 py-3 w-8">
                      <input
                        type="checkbox"
                        checked={allSelected}
                        onChange={() => setSelected(allSelected ? new Set() : new Set(items.map((i) => i.id)))}
                        aria-label="Select all"
                      />
                    </th>
                    <th className="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">Part</th>
                    <th className="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">Project</th>
                    <th className="px-4 py-3 text-right text-xs font-medium text-gray-500 uppercase tracking-wider">Qty</th>
                    <th className="px-4 py-3 text-right text-xs font-medium text-gray-500 uppercase tracking-wider">Unit price</th>
                    <th className="px-4 py-3 text-right text-xs font-medium text-gray-500 uppercase tracking-wider">Total</th>
                    <th className="px-4 py-3" />
                  </tr>
                </thead>
                <tbody className="divide-y divide-gray-50">
                  {items.map((item) => (
                    <tr key={item.id} className={selected.has(item.id) ? 'bg-blue-500/10' : 'hover:bg-gray-50'}>
                      <td className="px-4 py-3">
                        <input
                          type="checkbox"
                          checked={selected.has(item.id)}
                          onChange={() => toggle(item.id)}
                          aria-label={`Select ${item.partNumber}`}
                        />
                      </td>
                      <td className="px-4 py-3">
                        <Link to={`/parts/${item.partId}`} className="font-medium text-blue-600 hover:underline text-sm">
                          {item.partNumber}
                        </Link>
                        {item.description && <div className="text-xs text-gray-400 max-w-md truncate">{item.description}</div>}
                        {item.mpn && item.mpn !== item.partNumber && (
                          <div className="text-xs text-gray-400">
                            {item.manufacturer ? `${item.manufacturer} ` : ''}{item.mpn}
                          </div>
                        )}
                        {item.notes && <div className="text-xs text-gray-400 italic">{item.notes}</div>}
                      </td>
                      <td className="px-4 py-3 text-sm">
                        {item.projectId ? (
                          <Link to={`/projects/${item.projectId}`} className="text-blue-600 hover:underline">
                            {item.projectName}
                          </Link>
                        ) : (
                          <span className="text-gray-400">Stock</span>
                        )}
                      </td>
                      <td className="px-4 py-3 text-right text-sm text-gray-900">{item.quantity}</td>
                      <td className="px-4 py-3 text-right text-sm text-gray-700 whitespace-nowrap">
                        {item.unitPrice != null ? formatMoney(item.unitPrice, { precise: true }) : <span className="text-gray-400">—</span>}
                      </td>
                      <td className="px-4 py-3 text-right text-sm font-medium text-gray-900 whitespace-nowrap">
                        {item.totalPrice != null ? formatMoney(item.totalPrice) : <span className="text-gray-400">—</span>}
                      </td>
                      <td className="px-4 py-3 text-right">
                        <div className="flex items-center justify-end gap-1">
                          <button
                            onClick={() => openEdit(item)}
                            className="rounded px-2 py-1 text-xs text-blue-600 hover:bg-blue-500/10"
                          >
                            Edit
                          </button>
                          <button
                            onClick={() => setRemoving(item)}
                            className="rounded px-2 py-1 text-xs text-red-500 hover:bg-red-500/10"
                          >
                            Remove
                          </button>
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      ) : (
        <div className="rounded-lg border border-gray-200 bg-surface shadow-sm">
          {orders.length === 0 ? (
            <div className="px-6 py-8 text-center text-sm text-gray-400">
              No orders yet. Select items on the order list and put them on an order.
            </div>
          ) : (
            <div className="overflow-x-auto">
              <table className="min-w-full divide-y divide-gray-100">
                <thead className="bg-gray-50">
                  <tr>
                    <th className="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">Order</th>
                    <th className="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">Supplier</th>
                    <th className="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">Status</th>
                    <th className="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">Reference</th>
                    <th className="px-4 py-3 text-right text-xs font-medium text-gray-500 uppercase tracking-wider">Lines</th>
                    <th className="px-4 py-3 text-right text-xs font-medium text-gray-500 uppercase tracking-wider">Total</th>
                    <th className="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">Created</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-gray-50">
                  {orders.map((o) => (
                    <tr key={o.id} onClick={() => navigate(`/orders/${o.id}`)} className="cursor-pointer hover:bg-gray-50">
                      <td className="px-4 py-3 text-sm font-medium text-blue-600">#{o.id}</td>
                      <td className="px-4 py-3 text-sm text-gray-900">{o.supplierName}</td>
                      <td className="px-4 py-3">
                        <span className={`inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-medium ${ORDER_STATUS_COLORS[o.status]}`}>
                          {ORDER_STATUS_LABELS[o.status]}
                        </span>
                      </td>
                      <td className="px-4 py-3 text-sm text-gray-700">{o.supplierReference ?? '—'}</td>
                      <td className="px-4 py-3 text-right text-sm text-gray-700">{o.lineCount}</td>
                      <td className="px-4 py-3 text-right text-sm font-medium text-gray-900 whitespace-nowrap">{formatMoney(o.total)}</td>
                      <td className="px-4 py-3 text-sm text-gray-500 whitespace-nowrap">
                        {new Date(o.createdAt).toLocaleDateString()}
                        {o.createdBy && <span className="text-gray-400"> · {o.createdBy}</span>}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      )}

      {/* Add / edit item */}
      <Modal
        open={showItem}
        onClose={() => setShowItem(false)}
        title={editingId !== null ? `Edit ${form.partNumber}` : 'Add part to the order list'}
        wide
      >
        <div className="space-y-4">
          {editingId === null && (
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1">Part</label>
              <PartSearchPicker
                autoFocus
                onSelect={(p) => setForm((f) => ({ ...f, partId: p.id, partNumber: p.partNumber }))}
              />
              {form.partId > 0 && (
                <p className="mt-1 text-xs text-green-600">{form.partNumber} selected</p>
              )}
            </div>
          )}
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
            <div>
              <label className="block text-sm font-medium text-gray-700">Quantity</label>
              <NumberInput
                className={inputClass}
                value={form.quantity}
                onChange={(v) => setForm((f) => ({ ...f, quantity: v }))}
              />
            </div>
            <div>
              <label className="block text-sm font-medium text-gray-700">Unit price</label>
              <NumberInput
                decimal
                className={inputClass}
                value={form.unitPrice}
                onChange={(v) => setForm((f) => ({ ...f, unitPrice: v }))}
              />
              {editingId === null && (
                <p className="mt-1 text-xs text-gray-400">Leave empty to use the last price a supplier charged.</p>
              )}
            </div>
          </div>
          <div>
            <label className="block text-sm font-medium text-gray-700">For project</label>
            <select
              className={inputClass}
              value={form.projectId ?? ''}
              onChange={(e) => setForm((f) => ({ ...f, projectId: e.target.value ? Number(e.target.value) : null }))}
            >
              <option value="">— none (for stock) —</option>
              {projectOptions.map((p) => (
                <option key={p.id} value={p.id}>{p.name}</option>
              ))}
            </select>
          </div>
          <div>
            <label className="block text-sm font-medium text-gray-700">Notes</label>
            <input
              className={inputClass}
              value={form.notes}
              onChange={(e) => setForm((f) => ({ ...f, notes: e.target.value }))}
            />
          </div>
          <div className="flex justify-end gap-2 pt-2">
            <button
              onClick={() => setShowItem(false)}
              className="rounded-lg border border-gray-300 px-4 py-2 text-sm font-medium hover:bg-gray-50"
            >
              Cancel
            </button>
            <button
              onClick={handleSaveItem}
              disabled={busy || !form.partId || !form.quantity}
              className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-50"
            >
              {busy ? 'Saving…' : editingId !== null ? 'Update' : 'Add'}
            </button>
          </div>
        </div>
      </Modal>

      {/* Remove confirmation */}
      <Modal open={removing !== null} onClose={() => setRemoving(null)} title="Remove from order list">
        {removing && (
          <div className="space-y-4">
            <p className="text-sm text-gray-600">
              Take <span className="font-medium">{removing.quantity} × {removing.partNumber}</span> off
              the order list?
            </p>
            <div className="flex justify-end gap-2 pt-2">
              <button
                onClick={() => setRemoving(null)}
                className="rounded-lg border border-gray-300 px-4 py-2 text-sm font-medium hover:bg-gray-50"
              >
                Keep it
              </button>
              <button
                onClick={handleRemove}
                disabled={busy}
                className="rounded-lg bg-red-600 px-4 py-2 text-sm font-medium text-white hover:bg-red-700 disabled:opacity-50"
              >
                {busy ? 'Removing…' : 'Remove'}
              </button>
            </div>
          </div>
        )}
      </Modal>

      {/* Put selected items on an order */}
      <Modal open={showPutOnOrder} onClose={() => setShowPutOnOrder(false)} title="Put on an order">
        <div className="space-y-4">
          <p className="text-sm text-gray-600">
            {selected.size} {selected.size === 1 ? 'item' : 'items'} ({formatMoney(selectedTotal)}).
            Items for the same part become one line on the order, with the quantities added up.
          </p>
          <div className="space-y-2">
            {drafts.map((d) => (
              <label key={d.id} className="flex items-center gap-2 text-sm">
                <input
                  type="radio"
                  checked={targetOrderId === d.id}
                  onChange={() => setTargetOrderId(d.id)}
                />
                Add to draft order #{d.id} at {d.supplierName}
                <span className="text-gray-400">({d.lineCount} lines)</span>
              </label>
            ))}
            <label className="flex items-center gap-2 text-sm">
              <input type="radio" checked={targetOrderId === 'new'} onChange={() => setTargetOrderId('new')} />
              New order at
              <select
                className="rounded-lg border border-gray-300 px-2 py-1 text-sm"
                value={targetSupplierId ?? ''}
                onChange={(e) => { setTargetSupplierId(Number(e.target.value)); setTargetOrderId('new'); }}
              >
                {suppliers.map((s) => (
                  <option key={s.id} value={s.id}>{s.name}</option>
                ))}
              </select>
            </label>
            {suppliers.length === 0 && (
              <p className="text-xs text-amber-700">Add a supplier first (the Suppliers button above).</p>
            )}
          </div>
          <div className="flex justify-end gap-2 pt-2">
            <button
              onClick={() => setShowPutOnOrder(false)}
              className="rounded-lg border border-gray-300 px-4 py-2 text-sm font-medium hover:bg-gray-50"
            >
              Cancel
            </button>
            <button
              onClick={handlePutOnOrder}
              disabled={busy || (targetOrderId === 'new' && !targetSupplierId)}
              className="rounded-lg bg-green-600 px-4 py-2 text-sm font-medium text-white hover:bg-green-700 disabled:opacity-50"
            >
              {busy ? 'Working…' : 'Put on order'}
            </button>
          </div>
        </div>
      </Modal>

      {/* Suppliers */}
      <Modal open={showSuppliers} onClose={() => { setShowSuppliers(false); setRenaming(null); }} title="Suppliers">
        <div className="space-y-4">
          <ul className="divide-y divide-gray-100 rounded-lg border border-gray-200">
            {suppliers.map((s) => (
              <li key={s.id} className="flex items-center justify-between gap-2 px-3 py-2">
                {renaming?.id === s.id ? (
                  <>
                    <input
                      className="flex-1 rounded-lg border border-gray-300 px-2 py-1 text-sm"
                      value={renaming.name}
                      onChange={(e) => setRenaming({ id: s.id, name: e.target.value })}
                      onKeyDown={(e) => { if (e.key === 'Enter') handleRenameSupplier(); }}
                      autoFocus
                    />
                    <button onClick={handleRenameSupplier} className="rounded px-2 py-1 text-xs text-blue-600 hover:bg-blue-500/10">Save</button>
                    <button onClick={() => setRenaming(null)} className="rounded px-2 py-1 text-xs text-gray-600 hover:bg-gray-100">Cancel</button>
                  </>
                ) : (
                  <>
                    <span className="text-sm text-gray-900">{s.name}</span>
                    <div className="flex gap-1">
                      <button
                        onClick={() => setRenaming({ id: s.id, name: s.name })}
                        className="rounded px-2 py-1 text-xs text-blue-600 hover:bg-blue-500/10"
                      >
                        Rename
                      </button>
                      <button
                        onClick={() => handleDeleteSupplier(s.id)}
                        className="rounded px-2 py-1 text-xs text-red-500 hover:bg-red-500/10"
                      >
                        Delete
                      </button>
                    </div>
                  </>
                )}
              </li>
            ))}
            {suppliers.length === 0 && <li className="px-3 py-2 text-sm text-gray-400">No suppliers yet.</li>}
          </ul>
          <div className="flex gap-2">
            <input
              className="flex-1 rounded-lg border border-gray-300 px-3 py-2 text-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
              placeholder="New supplier name"
              value={newSupplier}
              onChange={(e) => setNewSupplier(e.target.value)}
              onKeyDown={(e) => { if (e.key === 'Enter') handleAddSupplier(); }}
            />
            <button
              onClick={handleAddSupplier}
              disabled={!newSupplier.trim()}
              className="rounded-lg bg-blue-600 px-3 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-50"
            >
              Add
            </button>
          </div>
          <p className="text-xs text-gray-400">
            A supplier something was ordered from cannot be deleted.
          </p>
        </div>
      </Modal>
    </div>
  );
}
