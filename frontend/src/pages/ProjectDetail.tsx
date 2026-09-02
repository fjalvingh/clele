import { useEffect, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import {
  activateProject,
  addProjectPart,
  cancelProject,
  deleteProject,
  getImportedBom,
  getParts,
  getProject,
  removeProjectPart,
  returnProjectPart,
  updateProjectPart,
} from '../api';
import {
  type ImportedBom,
  type Part,
  type Project,
  type ProjectPart,
  type ProjectPartRequest,
  type ProjectStatus,
} from '../api/types';
import Modal from '../components/Modal';
import NumberInput from '../components/NumberInput';
import { useSettings } from '../settings/SettingsContext';

// The quantity boxes are empty while being retyped, so the form holds them as nullable and the
// submit handlers coerce; the request types themselves stay strictly numeric.
type PartFormState = Omit<ProjectPartRequest, 'qtyPerInstance'> & { qtyPerInstance: number | null };

const STATUS_LABELS: Record<ProjectStatus, string> = {
  ACTIVE: 'Active',
  CANCELLED: 'Cancelled',
};

const STATUS_COLORS: Record<ProjectStatus, string> = {
  ACTIVE: 'bg-green-500/15 text-green-700',
  CANCELLED: 'bg-gray-500/15 text-gray-500',
};

export default function ProjectDetailPage() {
  const { id } = useParams<{ id: string }>();
  const projectId = Number(id);
  const navigate = useNavigate();
  const { formatMoney } = useSettings();

  const [project, setProject] = useState<Project | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  // Add / edit part modal state
  const [showPart, setShowPart] = useState(false);
  const [partSearch, setPartSearch] = useState('');
  const [searchResults, setSearchResults] = useState<Part[]>([]);
  const [searchLoading, setSearchLoading] = useState(false);
  const [partForm, setPartForm] = useState<PartFormState>({ partId: 0, qtyPerInstance: 1 });
  const [editingPartId, setEditingPartId] = useState<number | null>(null);
  const [partSaving, setPartSaving] = useState(false);

  // Return / remove / phase modal state
  const [returning, setReturning] = useState<ProjectPart | null>(null);
  const [returnQty, setReturnQty] = useState<number | null>(1);
  const [removing, setRemoving] = useState<ProjectPart | null>(null);
  const [showCancel, setShowCancel] = useState(false);
  const [showDelete, setShowDelete] = useState(false);
  const [busy, setBusy] = useState(false);

  // The imported BOM, if one has been uploaded. Fetched separately from the project because it is
  // its own resource and answers 204 when there is none — the common case for an older project.
  const [importedBom, setImportedBom] = useState<ImportedBom | null>(null);

  const load = () => {
    setLoading(true);
    getProject(projectId)
      .then(setProject)
      .catch((e: Error) => setError(e.message))
      .finally(() => setLoading(false));
    getImportedBom(projectId).then(setImportedBom).catch(() => setImportedBom(null));
  };

  useEffect(load, [projectId]);

  // Part search for the part list
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

  // ------------------------------------------------------------------
  // Actions
  // ------------------------------------------------------------------

  const handleSavePart = async () => {
    const qtyPerInstance = partForm.qtyPerInstance ?? 0;
    if (!partForm.partId || qtyPerInstance < 1) return;
    setPartSaving(true);
    try {
      const saved = editingPartId !== null
        ? await updateProjectPart(projectId, editingPartId, { ...partForm, qtyPerInstance })
        : await addProjectPart(projectId, { ...partForm, qtyPerInstance });
      if (saved.shortfall > 0) {
        setNotice(
          `${saved.partNumber}: only ${saved.qtyAllocated} of ${saved.totalNeeded} could be taken ` +
          'from stock. The rest is short.',
        );
      }
      setShowPart(false);
      setPartForm({ partId: 0, qtyPerInstance: 1 });
      setEditingPartId(null);
      setPartSearch('');
      setSearchResults([]);
      load();
    } catch (e) { setError((e as Error).message); }
    finally { setPartSaving(false); }
  };

  const handleReturn = async () => {
    if (!returning) return;
    const quantity = returnQty ?? 0;
    if (quantity < 1) return;
    setBusy(true);
    try {
      await returnProjectPart(projectId, returning.id, { quantity });
      setReturning(null);
      load();
    } catch (e) { setError((e as Error).message); }
    finally { setBusy(false); }
  };

  const handleRemove = async () => {
    if (!removing) return;
    setBusy(true);
    try {
      await removeProjectPart(projectId, removing.id);
      setRemoving(null);
      load();
    } catch (e) { setError((e as Error).message); }
    finally { setBusy(false); }
  };

  const handleCancel = async () => {
    setBusy(true);
    try {
      await cancelProject(projectId);
      setShowCancel(false);
      setNotice('Project cancelled. Every allocated part went back to the location it came from.');
      load();
    } catch (e) { setError((e as Error).message); }
    finally { setBusy(false); }
  };

  const handleActivate = async () => {
    setBusy(true);
    try {
      const updated = await activateProject(projectId);
      const short = (updated.parts ?? []).filter((p) => p.shortfall > 0).length;
      setNotice(
        short > 0
          ? `Project reactivated, but ${short} part${short === 1 ? '' : 's'} could not be taken ` +
            'from stock in full.'
          : 'Project reactivated. Every part was taken from stock again.',
      );
      load();
    } catch (e) { setError((e as Error).message); }
    finally { setBusy(false); }
  };

  const handleDelete = async () => {
    setBusy(true);
    try {
      await deleteProject(projectId);
      navigate('/projects');
    } catch (e) { setError((e as Error).message); setBusy(false); }
  };

  // ------------------------------------------------------------------
  // Render
  // ------------------------------------------------------------------

  if (loading) return <div className="p-8 text-gray-400 text-sm">Loading…</div>;
  if (!project) return <div className="p-8 text-red-600">{error ?? 'Project not found'}</div>;

  const parts: ProjectPart[] = project.parts ?? [];
  const isActive = project.status === 'ACTIVE';
  const shortCount = parts.filter((p) => p.shortfall > 0).length;

  return (
    <div className="p-4 md:p-8 max-w-5xl mx-auto space-y-6">
      {/* Breadcrumb */}
      <div className="text-sm text-gray-500">
        <Link to="/projects" className="hover:underline">Projects</Link>
        <span className="mx-1.5">›</span>
        <span className="text-gray-700">{project.name}</span>
      </div>

      {error && (
        <div className="rounded-lg border border-red-200 bg-red-500/10 px-4 py-3 text-sm text-red-700">
          {error}
          <button className="ml-2 underline" onClick={() => setError(null)}>Dismiss</button>
        </div>
      )}

      {notice && (
        <div className="rounded-lg border border-amber-200 bg-amber-500/10 px-4 py-3 text-sm text-amber-800">
          {notice}
          <button className="ml-2 underline" onClick={() => setNotice(null)}>Dismiss</button>
        </div>
      )}

      {/* Header card */}
      <div className="rounded-lg border border-gray-200 bg-surface p-6 shadow-sm">
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
              <span className="font-medium">{project.partCount}</span>{' '}
              {project.partCount === 1 ? 'part' : 'parts'}
              {project.totalStockValue != null && project.totalStockValue > 0 && (
                <> · <span className="font-medium">{formatMoney(project.totalStockValue)}</span> allocated</>
              )}
            </p>
            <p className="mt-1 text-xs text-gray-400">
              {isActive
                ? 'Parts on the list below are out of stock and held by this project.'
                : 'Cancelled: every part has gone back to stock. Nothing can be changed until it is reactivated.'}
            </p>
          </div>
          <div className="flex items-center gap-2 shrink-0">
            {isActive ? (
              <>
                <button
                  onClick={() => { setShowPart(true); setEditingPartId(null); setPartForm({ partId: 0, qtyPerInstance: 1 }); setPartSearch(''); }}
                  className="rounded-lg bg-blue-600 px-3 py-1.5 text-sm font-medium text-white hover:bg-blue-700"
                >
                  Add Part
                </button>
                <button
                  onClick={() => setShowCancel(true)}
                  className="rounded-lg border border-red-200 px-3 py-1.5 text-sm font-medium text-red-600 hover:bg-red-500/10"
                >
                  Cancel Project
                </button>
              </>
            ) : (
              <>
                <button
                  onClick={handleActivate}
                  disabled={busy}
                  className="rounded-lg bg-green-600 px-3 py-1.5 text-sm font-medium text-white hover:bg-green-700 disabled:opacity-50"
                >
                  Reactivate
                </button>
                <button
                  onClick={() => setShowDelete(true)}
                  className="rounded-lg border border-red-200 px-3 py-1.5 text-sm font-medium text-red-600 hover:bg-red-500/10"
                >
                  Delete
                </button>
              </>
            )}
          </div>
        </div>
      </div>

      {/* Imported BOM card — the uploaded file and its matching progress. Applying it feeds the
          project parts list below; the two are different lists and deliberately look it. */}
      <div className="rounded-lg border border-gray-200 bg-surface shadow-sm">
        <div className="flex flex-wrap items-center justify-between gap-3 border-b border-gray-100 px-6 py-4">
          <h2 className="font-semibold text-gray-900">Imported BOM</h2>
          <Link
            to={`/projects/${projectId}/bom`}
            className="rounded-lg bg-blue-600 px-3 py-1.5 text-sm font-medium text-white hover:bg-blue-700"
          >
            {importedBom ? 'Open BOM matching' : 'Import a BOM file'}
          </Link>
        </div>
        {importedBom ? (
          <div className="px-6 py-4">
            <p className="text-sm text-gray-700">
              <span className="font-medium">{importedBom.filename ?? 'BOM'}</span>
              {' · '}{importedBom.totalLines} lines
              {' · imported '}{new Date(importedBom.importedAt).toLocaleDateString()}
            </p>
            <div className="mt-2 flex flex-wrap gap-2 text-xs">
              <span className="rounded-full bg-green-500/15 px-2.5 py-0.5 font-medium text-green-700">
                {importedBom.matchedCount} matched
              </span>
              {importedBom.unmatchedCount > 0 && (
                <span className="rounded-full bg-amber-500/15 px-2.5 py-0.5 font-medium text-amber-800">
                  {importedBom.unmatchedCount} still to match
                </span>
              )}
              {importedBom.providedCount > 0 && (
                <span className="rounded-full bg-blue-500/15 px-2.5 py-0.5 font-medium text-blue-700">
                  {importedBom.providedCount} provided
                </span>
              )}
              {importedBom.excludedCount > 0 && (
                <span className="rounded-full bg-gray-500/15 px-2.5 py-0.5 font-medium text-gray-500">
                  {importedBom.excludedCount} excluded
                </span>
              )}
              {importedBom.changedCount > 0 && (
                <span className="rounded-full bg-purple-500/15 px-2.5 py-0.5 font-medium text-purple-700">
                  {importedBom.changedCount} changed — review
                </span>
              )}
            </div>
          </div>
        ) : (
          <div className="px-6 py-8 text-center text-sm text-gray-400">
            No BOM file imported. Upload your EDA tool's CSV export and match its lines to parts,
            rather than adding them one at a time below.
          </div>
        )}
      </div>

      {/* Project parts list */}
      <div className="rounded-lg border border-gray-200 bg-surface shadow-sm">
        <div className="flex flex-wrap items-center justify-between gap-3 border-b border-gray-100 px-6 py-4">
          <div>
            <h2 className="font-semibold text-gray-900">Project parts list</h2>
            {shortCount > 0 && (
              <p className="mt-0.5 text-xs text-amber-700">
                {shortCount} part{shortCount === 1 ? '' : 's'} could not be taken from stock in full.
              </p>
            )}
          </div>
          {isActive && (
            <button
              onClick={() => { setShowPart(true); setEditingPartId(null); setPartForm({ partId: 0, qtyPerInstance: 1 }); setPartSearch(''); }}
              className="rounded-lg bg-blue-600 px-3 py-1.5 text-sm font-medium text-white hover:bg-blue-700"
            >
              Add Part
            </button>
          )}
        </div>
        {parts.length === 0 ? (
          <div className="px-6 py-8 text-center text-sm text-gray-400">
            No parts on the list yet.
            {isActive && ' Use "Add Part" — it comes out of stock straight away.'}
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="min-w-full divide-y divide-gray-100">
              <thead className="bg-gray-50">
                <tr>
                  <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">Part</th>
                  <th className="px-6 py-3 text-right text-xs font-medium text-gray-500 uppercase tracking-wider">Qty/Instance</th>
                  <th className="px-6 py-3 text-right text-xs font-medium text-gray-500 uppercase tracking-wider">Needed</th>
                  <th className="px-6 py-3 text-right text-xs font-medium text-gray-500 uppercase tracking-wider">Allocated</th>
                  <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">Status</th>
                  {isActive && <th className="px-6 py-3" />}
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-50">
                {parts.map((entry) => (
                  <tr key={entry.id} className={entry.shortfall > 0 ? 'bg-amber-500/10' : 'hover:bg-gray-50'}>
                    <td className="px-6 py-3">
                      <Link to={`/parts/${entry.partId}`} className="font-medium text-blue-600 hover:underline text-sm">
                        {entry.partNumber}
                      </Link>
                      {entry.partName && <div className="text-xs text-gray-400">{entry.partName}</div>}
                      {entry.notes && <div className="text-xs text-gray-400 italic">{entry.notes}</div>}
                    </td>
                    <td className="px-6 py-3 text-right text-sm text-gray-700">{entry.qtyPerInstance}</td>
                    <td className="px-6 py-3 text-right text-sm text-gray-700">{entry.totalNeeded}</td>
                    <td className="px-6 py-3 text-right text-sm font-medium text-gray-900">{entry.qtyAllocated}</td>
                    <td className="px-6 py-3">
                      {entry.shortfall > 0 ? (
                        <span className="inline-flex items-center gap-1 rounded-full bg-amber-500/15 px-2 py-0.5 text-xs font-medium text-amber-800">
                          <svg className="h-3 w-3" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                            <path d="M10.29 3.86 1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0Z" />
                            <line x1="12" y1="9" x2="12" y2="13" />
                            <line x1="12" y1="17" x2="12.01" y2="17" />
                          </svg>
                          {entry.shortfall} short
                        </span>
                      ) : (
                        <span className="inline-flex items-center rounded-full bg-green-500/15 px-2 py-0.5 text-xs font-medium text-green-700">
                          Allocated
                        </span>
                      )}
                    </td>
                    {isActive && (
                      <td className="px-6 py-3 text-right">
                        <div className="flex items-center justify-end gap-1">
                          {entry.qtyAllocated > 0 && (
                            <button
                              onClick={() => { setReturning(entry); setReturnQty(entry.qtyAllocated); }}
                              className="rounded px-2 py-1 text-xs text-gray-600 hover:bg-gray-100"
                            >
                              Return
                            </button>
                          )}
                          <button
                            onClick={() => {
                              setEditingPartId(entry.id);
                              setPartForm({ partId: entry.partId, qtyPerInstance: entry.qtyPerInstance, notes: entry.notes });
                              setShowPart(true);
                            }}
                            className="rounded px-2 py-1 text-xs text-blue-600 hover:bg-blue-500/10"
                          >
                            Edit
                          </button>
                          <button
                            onClick={() => setRemoving(entry)}
                            className="rounded px-2 py-1 text-xs text-red-500 hover:bg-red-500/10"
                          >
                            Remove
                          </button>
                        </div>
                      </td>
                    )}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {/* Add / edit part modal */}
      <Modal
        open={showPart}
        onClose={() => setShowPart(false)}
        title={editingPartId ? 'Edit part list entry' : 'Add part to project'}
        wide
      >
        <div className="space-y-4">
          {editingPartId === null && (
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
                <div className="mt-1 max-h-64 overflow-auto rounded-lg border border-gray-200 bg-surface shadow-sm">
                  <table className="min-w-full divide-y divide-gray-100">
                    <thead className="bg-gray-50 sticky top-0">
                      <tr>
                        <th className="px-3 py-2 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">Part #</th>
                        <th className="px-3 py-2 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">Description</th>
                        <th className="px-3 py-2 text-right text-xs font-medium text-gray-500 uppercase tracking-wider">On hand</th>
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-gray-50">
                      {searchResults.map((p) => (
                        <tr
                          key={p.id}
                          onClick={() => {
                            setPartForm((f) => ({ ...f, partId: p.id }));
                            setPartSearch(p.partNumber);
                            setSearchResults([]);
                          }}
                          className={`cursor-pointer hover:bg-blue-500/10 ${partForm.partId === p.id ? 'bg-blue-500/10' : ''}`}
                        >
                          <td className="px-3 py-2 text-sm font-medium text-gray-900 whitespace-nowrap">{p.partNumber}</td>
                          <td className="px-3 py-2 text-sm text-gray-500 max-w-xs truncate">{p.description ?? '—'}</td>
                          <td className="px-3 py-2 text-sm text-right whitespace-nowrap">
                            <span className={(p.totalQuantity ?? 0) > 0 ? 'text-green-700 font-medium' : 'text-gray-400'}>
                              {p.totalQuantity ?? 0}
                            </span>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
              {partForm.partId > 0 && (
                <p className="mt-1 text-xs text-green-600">Part selected (ID {partForm.partId})</p>
              )}
            </div>
          )}
          <div>
            <label className="block text-sm font-medium text-gray-700">Qty per instance</label>
            <NumberInput
              className="mt-1 w-32 rounded-lg border border-gray-300 px-3 py-2 text-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
              value={partForm.qtyPerInstance}
              onChange={(v) => setPartForm((f) => ({ ...f, qtyPerInstance: v }))}
            />
            <p className="mt-1 text-xs text-gray-400">
              {(partForm.qtyPerInstance ?? 0) * project.instanceCount} will be taken from stock for{' '}
              {project.instanceCount} {project.instanceCount === 1 ? 'instance' : 'instances'}.
            </p>
          </div>
          <div>
            <label className="block text-sm font-medium text-gray-700">Notes</label>
            <input
              className="mt-1 w-full rounded-lg border border-gray-300 px-3 py-2 text-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
              value={partForm.notes ?? ''}
              onChange={(e) => setPartForm((f) => ({ ...f, notes: e.target.value }))}
            />
          </div>
          <div className="flex justify-end gap-2 pt-2">
            <button
              onClick={() => setShowPart(false)}
              className="rounded-lg border border-gray-300 px-4 py-2 text-sm font-medium hover:bg-gray-50"
            >
              Cancel
            </button>
            <button
              onClick={handleSavePart}
              disabled={partSaving || (!editingPartId && !partForm.partId) || !partForm.qtyPerInstance}
              className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-50"
            >
              {partSaving ? 'Saving…' : editingPartId ? 'Update' : 'Add'}
            </button>
          </div>
        </div>
      </Modal>

      {/* Return modal — asks how many when the project holds more than one, confirms otherwise. */}
      <Modal open={returning !== null} onClose={() => setReturning(null)} title="Return part to stock">
        {returning && (
          <div className="space-y-4">
            <p className="text-sm text-gray-600">
              {returning.qtyAllocated === 1 ? (
                <>Return the one <span className="font-medium">{returning.partNumber}</span> this
                project holds to the location it came from?</>
              ) : (
                <>This project holds <span className="font-medium">{returning.qtyAllocated}</span> ×{' '}
                <span className="font-medium">{returning.partNumber}</span>. How many go back to the
                locations they came from?</>
              )}
            </p>
            {returning.qtyAllocated > 1 && (
              <div>
                <label className="block text-sm font-medium text-gray-700">Quantity</label>
                <NumberInput
                  className="mt-1 w-32 rounded-lg border border-gray-300 px-3 py-2 text-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
                  value={returnQty}
                  onChange={setReturnQty}
                  autoFocus
                />
              </div>
            )}
            <p className="text-xs text-gray-400">
              The line stays on the list with its need intact, so it will show as short until the
              parts are fetched again.
            </p>
            <div className="flex justify-end gap-2 pt-2">
              <button
                onClick={() => setReturning(null)}
                className="rounded-lg border border-gray-300 px-4 py-2 text-sm font-medium hover:bg-gray-50"
              >
                Cancel
              </button>
              <button
                onClick={handleReturn}
                disabled={busy || !returnQty || returnQty > returning.qtyAllocated}
                className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-50"
              >
                {busy ? 'Returning…' : 'Return'}
              </button>
            </div>
          </div>
        )}
      </Modal>

      {/* Remove-from-list confirmation */}
      <Modal open={removing !== null} onClose={() => setRemoving(null)} title="Remove part from project">
        {removing && (
          <div className="space-y-4">
            <p className="text-sm text-gray-600">
              Take <span className="font-medium">{removing.partNumber}</span> off the project parts
              list?
              {removing.qtyAllocated > 0 && (
                <> The {removing.qtyAllocated} the project holds will go back to the locations they
                came from.</>
              )}
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

      {/* Cancel confirmation */}
      <Modal open={showCancel} onClose={() => setShowCancel(false)} title="Cancel project">
        <div className="space-y-4">
          <p className="text-sm text-gray-600">
            Every part this project holds goes back to the location it was taken from. The parts list
            keeps what the project needs, so reactivating fetches it all again.
          </p>
          <p className="text-sm text-gray-600">
            A cancelled project is read-only until it is reactivated.
          </p>
          <div className="flex justify-end gap-2 pt-2">
            <button
              onClick={() => setShowCancel(false)}
              className="rounded-lg border border-gray-300 px-4 py-2 text-sm font-medium hover:bg-gray-50"
            >
              Keep Working
            </button>
            <button
              onClick={handleCancel}
              disabled={busy}
              className="rounded-lg bg-red-600 px-4 py-2 text-sm font-medium text-white hover:bg-red-700 disabled:opacity-50"
            >
              {busy ? 'Cancelling…' : 'Cancel Project'}
            </button>
          </div>
        </div>
      </Modal>

      {/* Delete confirmation */}
      <Modal open={showDelete} onClose={() => setShowDelete(false)} title="Delete project">
        <div className="space-y-4">
          <p className="text-sm text-gray-600">
            Delete <span className="font-medium">{project.name}</span> for good? Its parts list and
            its imported BOM go with it, and this cannot be undone.
          </p>
          <p className="text-xs text-gray-400">
            The stock movements this project caused stay in the ledger and go on naming it.
          </p>
          <div className="flex justify-end gap-2 pt-2">
            <button
              onClick={() => setShowDelete(false)}
              className="rounded-lg border border-gray-300 px-4 py-2 text-sm font-medium hover:bg-gray-50"
            >
              Keep it
            </button>
            <button
              onClick={handleDelete}
              disabled={busy}
              className="rounded-lg bg-red-600 px-4 py-2 text-sm font-medium text-white hover:bg-red-700 disabled:opacity-50"
            >
              {busy ? 'Deleting…' : 'Delete Project'}
            </button>
          </div>
        </div>
      </Modal>
    </div>
  );
}
