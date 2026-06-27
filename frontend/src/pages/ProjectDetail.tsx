import { useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import {
  addBomEntry,
  cancelProject,
  completeProject,
  getMyLocations,
  getParts,
  getProject,
  pullStock,
  removeBomEntry,
  startBuild,
  updateBomEntry,
} from '../api';
import {
  type Location,
  type Part,
  type Project,
  type ProjectBomEntry,
  type ProjectBomRequest,
  type ProjectStatus,
  type ProjectStockEntry,
  type PullStockRequest,
} from '../api/types';
import Modal from '../components/Modal';
import { useSettings } from '../settings/SettingsContext';

const STATUS_LABELS: Record<ProjectStatus, string> = {
  PLANNING: 'Planning',
  BUILDING: 'Building',
  COMPLETED: 'Completed',
  CANCELLED: 'Cancelled',
};

const STATUS_COLORS: Record<ProjectStatus, string> = {
  PLANNING: 'bg-blue-100 text-blue-800',
  BUILDING: 'bg-yellow-100 text-yellow-800',
  COMPLETED: 'bg-green-100 text-green-800',
  CANCELLED: 'bg-gray-100 text-gray-500',
};

export default function ProjectDetailPage() {
  const { id } = useParams<{ id: string }>();
  const projectId = Number(id);
  const { formatMoney } = useSettings();

  const [project, setProject] = useState<Project | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // BOM modal state
  const [showBom, setShowBom] = useState(false);
  const [partSearch, setPartSearch] = useState('');
  const [searchResults, setSearchResults] = useState<Part[]>([]);
  const [searchLoading, setSearchLoading] = useState(false);
  const [bomForm, setBomForm] = useState<ProjectBomRequest>({ partId: 0, qtyPerInstance: 1 });
  const [editingBomId, setEditingBomId] = useState<number | null>(null);
  const [bomSaving, setBomSaving] = useState(false);

  // Pull stock modal state
  const [showPull, setShowPull] = useState(false);
  const [pullForm, setPullForm] = useState<PullStockRequest>({ partId: 0, locationId: 0, quantity: 1 });
  const [myLocations, setMyLocations] = useState<Location[]>([]);
  const [pullSaving, setPullSaving] = useState(false);

  // Cancel modal state
  const [showCancel, setShowCancel] = useState(false);
  const [returnIds, setReturnIds] = useState<Set<number>>(new Set());
  const [cancelSaving, setCancelSaving] = useState(false);

  // Transition state
  const [transitioning, setTransitioning] = useState(false);

  const load = () => {
    setLoading(true);
    getProject(projectId)
      .then(setProject)
      .catch((e: Error) => setError(e.message))
      .finally(() => setLoading(false));
  };

  useEffect(load, [projectId]);

  // Part search for BOM
  useEffect(() => {
    if (!partSearch.trim()) { setSearchResults([]); return; }
    const t = setTimeout(() => {
      setSearchLoading(true);
      getParts(partSearch)
        .then(setSearchResults)
        .catch(() => setSearchResults([]))
        .finally(() => setSearchLoading(false));
    }, 300);
    return () => clearTimeout(t);
  }, [partSearch]);

  // My locations for pull stock
  useEffect(() => {
    if (showPull) {
      getMyLocations().then(setMyLocations).catch(() => {});
    }
  }, [showPull]);

  // ------------------------------------------------------------------
  // Actions
  // ------------------------------------------------------------------

  const handleStartBuild = async () => {
    setTransitioning(true);
    try {
      const updated = await startBuild(projectId);
      setProject((p) => p ? { ...p, ...updated } : p);
    } catch (e) { setError((e as Error).message); }
    finally { setTransitioning(false); }
  };

  const handleComplete = async () => {
    setTransitioning(true);
    try {
      const updated = await completeProject(projectId);
      setProject((p) => p ? { ...p, ...updated } : p);
    } catch (e) { setError((e as Error).message); }
    finally { setTransitioning(false); }
  };

  const handleCancel = async () => {
    setCancelSaving(true);
    try {
      const updated = await cancelProject(projectId, { returnStockIds: Array.from(returnIds) });
      setProject((p) => p ? { ...p, ...updated } : p);
      setShowCancel(false);
      setReturnIds(new Set());
      load();
    } catch (e) { setError((e as Error).message); }
    finally { setCancelSaving(false); }
  };

  const handleAddBom = async () => {
    if (!bomForm.partId) return;
    setBomSaving(true);
    try {
      if (editingBomId !== null) {
        await updateBomEntry(projectId, editingBomId, bomForm);
      } else {
        await addBomEntry(projectId, bomForm);
      }
      setShowBom(false);
      setBomForm({ partId: 0, qtyPerInstance: 1 });
      setEditingBomId(null);
      setPartSearch('');
      setSearchResults([]);
      load();
    } catch (e) { setError((e as Error).message); }
    finally { setBomSaving(false); }
  };

  const handleRemoveBom = async (bomId: number) => {
    try {
      await removeBomEntry(projectId, bomId);
      load();
    } catch (e) { setError((e as Error).message); }
  };

  const handlePullStock = async () => {
    if (!pullForm.partId || !pullForm.locationId) return;
    setPullSaving(true);
    try {
      await pullStock(projectId, pullForm);
      setShowPull(false);
      setPullForm({ partId: 0, locationId: 0, quantity: 1 });
      load();
    } catch (e) { setError((e as Error).message); }
    finally { setPullSaving(false); }
  };

  // ------------------------------------------------------------------
  // Render
  // ------------------------------------------------------------------

  if (loading) return <div className="p-8 text-gray-400 text-sm">Loading…</div>;
  if (!project) return <div className="p-8 text-red-600">{error ?? 'Project not found'}</div>;

  const bom: ProjectBomEntry[] = project.bom ?? [];
  const stock: ProjectStockEntry[] = project.stock ?? [];
  const totalStockValue = stock
    .filter((s) => s.unitPrice != null)
    .reduce((sum, s) => sum + (s.unitPrice ?? 0) * s.quantity, 0);

  const canEdit = project.status === 'PLANNING';
  const canBuild = project.status === 'BUILDING';
  const isActive = project.status === 'PLANNING' || project.status === 'BUILDING';

  return (
    <div className="p-8 max-w-5xl mx-auto space-y-6">
      {/* Breadcrumb */}
      <div className="text-sm text-gray-500">
        <Link to="/projects" className="hover:underline">Projects</Link>
        <span className="mx-1.5">›</span>
        <span className="text-gray-700">{project.name}</span>
      </div>

      {error && (
        <div className="rounded-lg border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700">
          {error}
          <button className="ml-2 underline" onClick={() => setError(null)}>Dismiss</button>
        </div>
      )}

      {/* Header card */}
      <div className="rounded-lg border border-gray-200 bg-white p-6 shadow-sm">
        <div className="flex items-start justify-between gap-4">
          <div className="flex-1 min-w-0">
            <div className="flex items-center gap-3">
              <h1 className="text-xl font-bold text-gray-900 truncate">{project.name}</h1>
              <span className={`inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-medium ${STATUS_COLORS[project.status]}`}>
                {STATUS_LABELS[project.status]}
              </span>
            </div>
            {project.description && (
              <p className="mt-1 text-sm text-gray-500">{project.description}</p>
            )}
            <p className="mt-2 text-sm text-gray-600">
              <span className="font-medium">{project.instanceCount}</span>{' '}
              {project.instanceCount === 1 ? 'instance' : 'instances'} ·{' '}
              <span className="font-medium">{project.bomPartCount}</span> BOM parts
              {totalStockValue > 0 && (
                <> · <span className="font-medium">{formatMoney(totalStockValue)}</span> in stock</>
              )}
            </p>
          </div>
          <div className="flex items-center gap-2 shrink-0">
            {project.status === 'PLANNING' && (
              <button
                onClick={handleStartBuild}
                disabled={transitioning}
                className="rounded-lg bg-yellow-500 px-3 py-1.5 text-sm font-medium text-white hover:bg-yellow-600 disabled:opacity-50"
              >
                Start Build
              </button>
            )}
            {project.status === 'BUILDING' && (
              <>
                <button
                  onClick={() => setShowPull(true)}
                  className="rounded-lg bg-blue-600 px-3 py-1.5 text-sm font-medium text-white hover:bg-blue-700"
                >
                  Pull Stock
                </button>
                <button
                  onClick={handleComplete}
                  disabled={transitioning}
                  className="rounded-lg bg-green-600 px-3 py-1.5 text-sm font-medium text-white hover:bg-green-700 disabled:opacity-50"
                >
                  Complete
                </button>
              </>
            )}
            {isActive && (
              <button
                onClick={() => { setShowCancel(true); setReturnIds(new Set()); }}
                className="rounded-lg border border-red-200 px-3 py-1.5 text-sm font-medium text-red-600 hover:bg-red-50"
              >
                Cancel Project
              </button>
            )}
          </div>
        </div>
      </div>

      {/* BOM card */}
      <div className="rounded-lg border border-gray-200 bg-white shadow-sm">
        <div className="flex items-center justify-between border-b border-gray-100 px-6 py-4">
          <h2 className="font-semibold text-gray-900">Bill of Materials</h2>
          {canEdit && (
            <button
              onClick={() => { setShowBom(true); setEditingBomId(null); setBomForm({ partId: 0, qtyPerInstance: 1 }); setPartSearch(''); }}
              className="rounded-lg bg-blue-600 px-3 py-1.5 text-sm font-medium text-white hover:bg-blue-700"
            >
              Add Part
            </button>
          )}
        </div>
        {bom.length === 0 ? (
          <div className="px-6 py-8 text-center text-sm text-gray-400">
            No parts in BOM yet.{canEdit && ' Use "Add Part" to define what this project needs.'}
          </div>
        ) : (
          <table className="min-w-full divide-y divide-gray-100">
            <thead className="bg-gray-50">
              <tr>
                <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">Part</th>
                <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">Qty/Instance</th>
                <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">Total Needed</th>
                <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">Pulled</th>
                <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">Status</th>
                {canEdit && <th className="px-6 py-3" />}
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-50">
              {bom.map((entry) => {
                const remaining = entry.totalNeeded - entry.pulledTotal;
                const done = entry.pulledTotal >= entry.totalNeeded;
                return (
                  <tr key={entry.id} className="hover:bg-gray-50">
                    <td className="px-6 py-3">
                      <Link to={`/parts/${entry.partId}`} className="font-medium text-blue-600 hover:underline text-sm">
                        {entry.partNumber}
                      </Link>
                      <div className="text-xs text-gray-400">{entry.partName}</div>
                      {entry.notes && <div className="text-xs text-gray-400 italic">{entry.notes}</div>}
                    </td>
                    <td className="px-6 py-3 text-sm text-gray-700">{entry.qtyPerInstance}</td>
                    <td className="px-6 py-3 text-sm text-gray-700">{entry.totalNeeded}</td>
                    <td className="px-6 py-3 text-sm text-gray-700">{entry.pulledTotal}</td>
                    <td className="px-6 py-3">
                      {done ? (
                        <span className="inline-flex items-center rounded-full bg-green-100 px-2 py-0.5 text-xs font-medium text-green-700">Done</span>
                      ) : entry.pulledTotal > 0 ? (
                        <span className="inline-flex items-center rounded-full bg-yellow-100 px-2 py-0.5 text-xs font-medium text-yellow-700">
                          Need {remaining} more
                        </span>
                      ) : (
                        <span className="inline-flex items-center rounded-full bg-gray-100 px-2 py-0.5 text-xs font-medium text-gray-500">Not pulled</span>
                      )}
                    </td>
                    {canEdit && (
                      <td className="px-6 py-3 text-right">
                        <div className="flex items-center justify-end gap-1">
                          <button
                            onClick={() => {
                              setEditingBomId(entry.id);
                              setBomForm({ partId: entry.partId, qtyPerInstance: entry.qtyPerInstance, notes: entry.notes });
                              setShowBom(true);
                            }}
                            className="rounded px-2 py-1 text-xs text-blue-600 hover:bg-blue-50"
                          >
                            Edit
                          </button>
                          <button
                            onClick={() => handleRemoveBom(entry.id)}
                            className="rounded px-2 py-1 text-xs text-red-500 hover:bg-red-50"
                          >
                            Remove
                          </button>
                        </div>
                      </td>
                    )}
                  </tr>
                );
              })}
            </tbody>
          </table>
        )}
      </div>

      {/* Stock card — only shown if any stock has been pulled */}
      {(stock.length > 0 || project.status !== 'PLANNING') && (
        <div className="rounded-lg border border-gray-200 bg-white shadow-sm">
          <div className="flex items-center justify-between border-b border-gray-100 px-6 py-4">
            <h2 className="font-semibold text-gray-900">Parts in Project</h2>
            {canBuild && (
              <button
                onClick={() => setShowPull(true)}
                className="rounded-lg bg-blue-600 px-3 py-1.5 text-sm font-medium text-white hover:bg-blue-700"
              >
                Pull More Stock
              </button>
            )}
          </div>
          {stock.length === 0 ? (
            <div className="px-6 py-8 text-center text-sm text-gray-400">
              No stock pulled yet. Use "Pull Stock" to move parts from a location into the project.
            </div>
          ) : (
            <table className="min-w-full divide-y divide-gray-100">
              <thead className="bg-gray-50">
                <tr>
                  <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">Part</th>
                  <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">From</th>
                  <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">Qty</th>
                  <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">Unit Price</th>
                  <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">Added</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-50">
                {stock.map((s) => (
                  <tr key={s.id} className="hover:bg-gray-50">
                    <td className="px-6 py-3">
                      <Link to={`/parts/${s.partId}`} className="font-medium text-blue-600 hover:underline text-sm">
                        {s.partNumber}
                      </Link>
                      <div className="text-xs text-gray-400">{s.partName}</div>
                    </td>
                    <td className="px-6 py-3 text-sm text-gray-600" title={s.locationBreadcrumb}>
                      {s.locationBreadcrumb || s.locationName}
                    </td>
                    <td className="px-6 py-3 text-sm font-medium text-gray-900">{s.quantity}</td>
                    <td className="px-6 py-3 text-sm text-gray-600">
                      {s.unitPrice != null ? formatMoney(s.unitPrice) : '—'}
                    </td>
                    <td className="px-6 py-3 text-xs text-gray-400">
                      {new Date(s.addedAt).toLocaleDateString()}
                      {s.addedByName && <span className="ml-1">by {s.addedByName}</span>}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      )}

      {/* Add/Edit BOM entry modal */}
      <Modal open={showBom} onClose={() => setShowBom(false)} title={editingBomId ? 'Edit BOM Entry' : 'Add Part to BOM'}>
        <div className="space-y-4">
          {editingBomId === null && (
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1">Search Part</label>
              <input
                className="w-full rounded-lg border border-gray-300 px-3 py-2 text-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
                placeholder="Type part number or name…"
                value={partSearch}
                onChange={(e) => setPartSearch(e.target.value)}
                autoFocus
              />
              {searchLoading && <p className="text-xs text-gray-400 mt-1">Searching…</p>}
              {searchResults.length > 0 && (
                <div className="mt-1 max-h-48 overflow-auto rounded-lg border border-gray-200 bg-white shadow-sm">
                  {searchResults.map((p) => (
                    <button
                      key={p.id}
                      onClick={() => {
                        setBomForm((f) => ({ ...f, partId: p.id }));
                        setPartSearch(p.partNumber);
                        setSearchResults([]);
                      }}
                      className={`flex w-full items-start gap-2 px-3 py-2 text-left hover:bg-blue-50 ${bomForm.partId === p.id ? 'bg-blue-50' : ''}`}
                    >
                      <span className="text-sm font-medium text-gray-900">{p.partNumber}</span>
                      {p.description && <span className="text-sm text-gray-500">{p.description}</span>}
                    </button>
                  ))}
                </div>
              )}
              {bomForm.partId > 0 && (
                <p className="mt-1 text-xs text-green-600">Part selected (ID {bomForm.partId})</p>
              )}
            </div>
          )}
          <div>
            <label className="block text-sm font-medium text-gray-700">Qty per instance</label>
            <input
              type="number"
              min={1}
              className="mt-1 w-32 rounded-lg border border-gray-300 px-3 py-2 text-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
              value={bomForm.qtyPerInstance}
              onChange={(e) => setBomForm((f) => ({ ...f, qtyPerInstance: parseInt(e.target.value) || 1 }))}
            />
          </div>
          <div>
            <label className="block text-sm font-medium text-gray-700">Notes</label>
            <input
              className="mt-1 w-full rounded-lg border border-gray-300 px-3 py-2 text-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
              value={bomForm.notes ?? ''}
              onChange={(e) => setBomForm((f) => ({ ...f, notes: e.target.value }))}
            />
          </div>
          <div className="flex justify-end gap-2 pt-2">
            <button
              onClick={() => setShowBom(false)}
              className="rounded-lg border border-gray-300 px-4 py-2 text-sm font-medium hover:bg-gray-50"
            >
              Cancel
            </button>
            <button
              onClick={handleAddBom}
              disabled={bomSaving || (!editingBomId && !bomForm.partId)}
              className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-50"
            >
              {bomSaving ? 'Saving…' : editingBomId ? 'Update' : 'Add'}
            </button>
          </div>
        </div>
      </Modal>

      {/* Pull stock modal */}
      <Modal open={showPull} onClose={() => setShowPull(false)} title="Pull Stock into Project">
        <div className="space-y-4">
          <div>
            <label className="block text-sm font-medium text-gray-700">Part (from BOM)</label>
            <select
              className="mt-1 w-full rounded-lg border border-gray-300 px-3 py-2 text-sm focus:border-blue-500 focus:outline-none"
              value={pullForm.partId || ''}
              onChange={(e) => setPullForm((f) => ({ ...f, partId: Number(e.target.value) }))}
            >
              <option value="">Select a part…</option>
              {bom.map((b) => (
                <option key={b.partId} value={b.partId}>
                  {b.partNumber} — {b.partName}
                </option>
              ))}
              <option disabled>──────────</option>
              <option value="-1">Other part (enter ID manually)</option>
            </select>
          </div>
          {pullForm.partId === -1 && (
            <div>
              <label className="block text-sm font-medium text-gray-700">Part ID</label>
              <input
                type="number"
                className="mt-1 w-32 rounded-lg border border-gray-300 px-3 py-2 text-sm focus:border-blue-500 focus:outline-none"
                placeholder="ID"
                onChange={(e) => setPullForm((f) => ({ ...f, partId: Number(e.target.value) }))}
              />
            </div>
          )}
          <div>
            <label className="block text-sm font-medium text-gray-700">Source Location</label>
            <select
              className="mt-1 w-full rounded-lg border border-gray-300 px-3 py-2 text-sm focus:border-blue-500 focus:outline-none"
              value={pullForm.locationId || ''}
              onChange={(e) => setPullForm((f) => ({ ...f, locationId: Number(e.target.value) }))}
            >
              <option value="">Select a location…</option>
              {myLocations.map((l) => (
                <option key={l.id} value={l.id}>{l.breadcrumb}</option>
              ))}
            </select>
          </div>
          <div>
            <label className="block text-sm font-medium text-gray-700">Quantity</label>
            <input
              type="number"
              min={1}
              className="mt-1 w-32 rounded-lg border border-gray-300 px-3 py-2 text-sm focus:border-blue-500 focus:outline-none"
              value={pullForm.quantity}
              onChange={(e) => setPullForm((f) => ({ ...f, quantity: parseInt(e.target.value) || 1 }))}
            />
          </div>
          <div>
            <label className="block text-sm font-medium text-gray-700">Unit Price (optional)</label>
            <input
              type="number"
              step="0.0001"
              min={0}
              className="mt-1 w-40 rounded-lg border border-gray-300 px-3 py-2 text-sm focus:border-blue-500 focus:outline-none"
              placeholder="Leave blank to use WAC"
              onChange={(e) => setPullForm((f) => ({ ...f, unitPrice: e.target.value ? Number(e.target.value) : null }))}
            />
          </div>
          <div className="flex justify-end gap-2 pt-2">
            <button
              onClick={() => setShowPull(false)}
              className="rounded-lg border border-gray-300 px-4 py-2 text-sm font-medium hover:bg-gray-50"
            >
              Cancel
            </button>
            <button
              onClick={handlePullStock}
              disabled={pullSaving || !pullForm.partId || !pullForm.locationId}
              className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-50"
            >
              {pullSaving ? 'Pulling…' : 'Pull Stock'}
            </button>
          </div>
        </div>
      </Modal>

      {/* Cancel modal */}
      <Modal open={showCancel} onClose={() => setShowCancel(false)} title="Cancel Project">
        <div className="space-y-4">
          <p className="text-sm text-gray-600">
            Choose which pulled parts to return to their original locations. Unchecked parts remain consumed.
          </p>
          {stock.length === 0 ? (
            <p className="text-sm text-gray-400">No stock to return.</p>
          ) : (
            <div className="max-h-64 overflow-auto space-y-2">
              {stock.map((s) => (
                <label key={s.id} className="flex items-center gap-3 rounded-lg border border-gray-200 px-3 py-2 cursor-pointer hover:bg-gray-50">
                  <input
                    type="checkbox"
                    checked={returnIds.has(s.id)}
                    onChange={(e) => {
                      setReturnIds((prev) => {
                        const next = new Set(prev);
                        if (e.target.checked) next.add(s.id);
                        else next.delete(s.id);
                        return next;
                      });
                    }}
                    className="h-4 w-4 rounded border-gray-300 text-blue-600"
                  />
                  <div className="flex-1 min-w-0">
                    <span className="text-sm font-medium text-gray-900">{s.partNumber}</span>
                    <span className="text-sm text-gray-500 ml-1">{s.partName}</span>
                    <div className="text-xs text-gray-400">
                      {s.quantity} × from {s.locationBreadcrumb || s.locationName}
                    </div>
                  </div>
                </label>
              ))}
            </div>
          )}
          {stock.length > 0 && (
            <div className="flex gap-2 text-xs text-gray-500">
              <button
                onClick={() => setReturnIds(new Set(stock.map((s) => s.id)))}
                className="underline hover:text-gray-700"
              >
                Select all
              </button>
              <button
                onClick={() => setReturnIds(new Set())}
                className="underline hover:text-gray-700"
              >
                Clear
              </button>
            </div>
          )}
          <div className="flex justify-end gap-2 pt-2">
            <button
              onClick={() => setShowCancel(false)}
              className="rounded-lg border border-gray-300 px-4 py-2 text-sm font-medium hover:bg-gray-50"
            >
              Keep Working
            </button>
            <button
              onClick={handleCancel}
              disabled={cancelSaving}
              className="rounded-lg bg-red-600 px-4 py-2 text-sm font-medium text-white hover:bg-red-700 disabled:opacity-50"
            >
              {cancelSaving ? 'Cancelling…' : 'Cancel Project'}
            </button>
          </div>
        </div>
      </Modal>
    </div>
  );
}
