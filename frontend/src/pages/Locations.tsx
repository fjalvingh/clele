import { useEffect, useState } from 'react';
import {
  createLocation,
  deleteLocation,
  getLocations,
  getLocationStats,
  getLocationTree,
  mergeLocation,
  updateLocation,
} from '../api';
import type { Location, LocationRequest, LocationStats, LocationTree } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import FormField from '../components/FormField';
import Modal from '../components/Modal';
import { useSettings } from '../settings/SettingsContext';

// ---- Tree node component ----
interface TreeNodeProps {
  node: LocationTree;
  onEdit: (loc: Location) => void;
  onDelete: (loc: Location) => void;
  onMerge: (loc: Location) => void;
  onAddChild: (parentId: number) => void;
  canManage: (loc: Location) => boolean;
  locations: Location[];
  stats: Map<number, LocationStats>;
}

function TreeNode({
  node, onEdit, onDelete, onMerge, onAddChild, canManage, locations, stats,
}: TreeNodeProps) {
  const { formatMoney } = useSettings();
  const [expanded, setExpanded] = useState(true);
  const hasChildren = node.children && node.children.length > 0;
  const fullLoc = locations.find((l) => l.id === node.id);
  const manageable = fullLoc ? canManage(fullLoc) : false;
  // Totals cover the whole subtree, so a collapsed parent still accounts for everything below it;
  // when some of it sits deeper, the tooltip splits out what is at this location itself.
  const stat = stats.get(node.id);
  const empty = !stat || stat.totalParts === 0;
  const rolledUp = !!stat && hasChildren && stat.directParts !== stat.totalParts;

  return (
    <div className="ml-4">
      <div className="flex items-center gap-2 rounded px-2 py-1 hover:bg-gray-50 group">
        <button
          className="w-4 text-gray-400 text-xs"
          onClick={() => setExpanded(!expanded)}
        >
          {hasChildren ? (expanded ? '▼' : '▶') : '•'}
        </button>
        <span className="flex-1 text-sm text-gray-800 font-medium">{node.name}</span>
        {node.description && (
          <span className="hidden text-xs text-gray-400 group-hover:inline">{node.description}</span>
        )}
        <div className="flex gap-1 opacity-0 group-hover:opacity-100 transition-opacity">
          {manageable && (
            <button
              onClick={() => onAddChild(node.id)}
              className="rounded px-2 py-0.5 text-xs text-green-600 hover:bg-green-50"
            >
              + Sub
            </button>
          )}
          {manageable && fullLoc && (
            <button
              onClick={() => onEdit(fullLoc)}
              className="rounded px-2 py-0.5 text-xs text-blue-600 hover:bg-blue-50"
            >
              Edit
            </button>
          )}
          {manageable && fullLoc && (
            <button
              onClick={() => onMerge(fullLoc)}
              className="rounded px-2 py-0.5 text-xs text-amber-600 hover:bg-amber-50"
            >
              Merge into
            </button>
          )}
          {manageable && fullLoc && (
            <button
              onClick={() => onDelete(fullLoc)}
              className="rounded px-2 py-0.5 text-xs text-red-600 hover:bg-red-50"
            >
              Delete
            </button>
          )}
        </div>
        <span
          className={`flex shrink-0 items-center gap-4 text-xs tabular-nums ${
            empty ? 'text-gray-300' : 'text-gray-500'
          }`}
          title={
            rolledUp && stat
              ? `Directly here: ${stat.directParts} parts, ${stat.directQuantity} on hand, ` +
                formatMoney(stat.directStockValue)
              : undefined
          }
        >
          <span className="w-20 text-right">{stat ? `${stat.totalParts} parts` : ''}</span>
          <span className="w-24 text-right">{stat ? `${stat.totalQuantity} on hand` : ''}</span>
          <span className="w-24 text-right">{stat ? formatMoney(stat.totalStockValue) : ''}</span>
        </span>
      </div>
      {hasChildren && expanded && (
        <div className="border-l border-gray-200 ml-2">
          {node.children.map((child) => (
            <TreeNode
              key={child.id}
              node={child}
              onEdit={onEdit}
              onDelete={onDelete}
              onMerge={onMerge}
              onAddChild={onAddChild}
              canManage={canManage}
              locations={locations}
              stats={stats}
            />
          ))}
        </div>
      )}
    </div>
  );
}

// ---- Hierarchical parent selector ----
interface ParentOption {
  id: number;
  label: string;
}

// Build an indented option list of candidate parents. Locations belong to the organisation rather
// than to a user, so every location in it is eligible — except the location being edited and its
// whole subtree (a node can't be its own ancestor).
function buildParentOptions(
  nodes: LocationTree[],
  excludeId: number | null,
  depth = 0
): ParentOption[] {
  const options: ParentOption[] = [];
  for (const node of nodes) {
    if (node.id === excludeId) continue; // skip this node and its entire subtree
    const prefix = depth > 0 ? '  '.repeat(depth) + '└ ' : '';
    options.push({ id: node.id, label: prefix + node.name });
    options.push(...buildParentOptions(node.children, excludeId, depth + 1));
  }
  return options;
}

const emptyForm: LocationRequest = { name: '', description: '', parentId: null };

export default function LocationsPage() {
  const { hasPermission } = useAuth();
  // Locations are shared across the organisation, so any member who may edit parts — or administer
  // the organisation — may manage them.
  const canManage = (_loc: Location) =>
    hasPermission('ORG_ADMIN') || hasPermission('PARTS_EDIT');
  const [tree, setTree] = useState<LocationTree[]>([]);
  const [locations, setLocations] = useState<Location[]>([]);
  const [stats, setStats] = useState<Map<number, LocationStats>>(new Map());
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [modalOpen, setModalOpen] = useState(false);
  const [editing, setEditing] = useState<Location | null>(null);
  const [form, setForm] = useState<LocationRequest>(emptyForm);
  const [saving, setSaving] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);
  const [mergeSource, setMergeSource] = useState<Location | null>(null);
  const [mergeTarget, setMergeTarget] = useState<number | ''>('');
  const [merging, setMerging] = useState(false);
  const [mergeError, setMergeError] = useState<string | null>(null);

  const load = () => {
    setLoading(true);
    Promise.all([getLocationTree(), getLocations(), getLocationStats()])
      .then(([t, l, st]) => {
        setTree(t);
        setLocations(l);
        setStats(new Map(st.map((row) => [row.locationId, row])));
      })
      .catch((e: Error) => setError(e.message))
      .finally(() => setLoading(false));
  };

  useEffect(load, []);

  const openCreate = (parentId: number | null = null) => {
    setEditing(null);
    setForm({ ...emptyForm, parentId });
    setFormError(null);
    setModalOpen(true);
  };

  const openEdit = (loc: Location) => {
    setEditing(loc);
    setForm({
      name: loc.name,
      description: loc.description ?? '',
      parentId: loc.parentId ?? null,
    });
    setFormError(null);
    setModalOpen(true);
  };

  const handleSave = async () => {
    setSaving(true);
    setFormError(null);
    try {
      if (editing) {
        await updateLocation(editing.id, form);
      } else {
        await createLocation(form);
      }
      setModalOpen(false);
      load();
    } catch (e: unknown) {
      setFormError((e as Error).message);
    } finally {
      setSaving(false);
    }
  };

  const handleDelete = async (loc: Location) => {
    if (!confirm(`Delete location "${loc.name}"?`)) return;
    try {
      await deleteLocation(loc.id);
      load();
    } catch (e: unknown) {
      alert((e as Error).message);
    }
  };

  const openMerge = (loc: Location) => {
    setMergeSource(loc);
    setMergeTarget('');
    setMergeError(null);
  };

  const handleMerge = async () => {
    if (!mergeSource || !mergeTarget) return;
    setMerging(true);
    setMergeError(null);
    try {
      await mergeLocation(mergeSource.id, Number(mergeTarget));
      setMergeSource(null);
      load();
    } catch (e: unknown) {
      setMergeError((e as Error).message);
    } finally {
      setMerging(false);
    }
  };

  // Merge targets: every other location in the organisation, labelled with its full path.
  const mergeTargets = locations
    .filter((l) => l.id !== mergeSource?.id)
    .sort((a, b) => a.breadcrumb.localeCompare(b.breadcrumb));

  // Parent candidates: any location in the organisation, minus the edited node's subtree.
  const parentOptions = buildParentOptions(tree, editing?.id ?? null);

  return (
    <div className="p-4 md:p-8">
      <div className="mb-6 flex items-center justify-between">
        <h1 className="text-2xl font-bold text-gray-900">Locations</h1>
        <button
          onClick={() => openCreate(null)}
          className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700"
        >
          + New Location
        </button>
      </div>

      {loading && <p className="text-gray-500">Loading...</p>}
      {error && <p className="text-red-600">{error}</p>}

      {!loading && (
        <div className="rounded-xl border border-gray-200 bg-surface p-4 shadow-sm">
          {tree.length === 0 ? (
            <p className="text-sm text-gray-400">No locations yet. Create one to get started.</p>
          ) : (
            <>
              {/* Captions for the stat block each row renders on the right. The figures roll up the
                  whole subtree, matching the dashboard's per-location table. */}
              <div className="mb-1 flex items-center gap-2 border-b border-gray-100 px-2 pb-1 text-xs font-semibold uppercase tracking-wider text-gray-400">
                <span className="w-4" />
                <span className="flex-1">Location</span>
                <span className="w-20 text-right">Parts</span>
                <span className="w-24 text-right">On Hand</span>
                <span className="w-24 text-right">Stock Value</span>
              </div>
              {tree.map((root) => (
                <TreeNode
                  key={root.id}
                  node={root}
                  onEdit={openEdit}
                  onDelete={handleDelete}
                  onMerge={openMerge}
                  onAddChild={(parentId) => openCreate(parentId)}
                  canManage={canManage}
                  locations={locations}
                  stats={stats}
                />
              ))}
            </>
          )}
        </div>
      )}

      <Modal
        open={modalOpen}
        onClose={() => setModalOpen(false)}
        title={editing ? 'Edit Location' : 'New Location'}
      >
        <FormField
          label="Name *"
          value={form.name}
          onChange={(e) => setForm({ ...form, name: e.target.value })}
          placeholder="e.g. Cupboard C"
        />
        <FormField
          as="textarea"
          label="Description"
          value={form.description}
          onChange={(e) => setForm({ ...form, description: e.target.value })}
          rows={2}
          placeholder="Optional description"
        />
        <FormField
          as="select"
          label="Parent Location"
          value={form.parentId ?? ''}
          onChange={(e) =>
            setForm({ ...form, parentId: e.target.value ? Number(e.target.value) : null })
          }
        >
          <option value="">— None (top level) —</option>
          {parentOptions.map((opt) => (
            <option key={opt.id} value={opt.id}>
              {opt.label}
            </option>
          ))}
        </FormField>
        {formError && <p className="mb-3 text-sm text-red-600">{formError}</p>}
        <div className="flex justify-end gap-3">
          <button
            onClick={() => setModalOpen(false)}
            className="rounded-lg border border-gray-300 px-4 py-2 text-sm hover:bg-gray-50"
          >
            Cancel
          </button>
          <button
            onClick={handleSave}
            disabled={saving || !form.name.trim()}
            className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-50"
          >
            {saving ? 'Saving…' : 'Save'}
          </button>
        </div>
      </Modal>

      <Modal
        open={mergeSource !== null}
        onClose={() => setMergeSource(null)}
        title={`Merge "${mergeSource?.name ?? ''}" into…`}
      >
        <p className="mb-4 text-sm text-gray-600">
          All stock in <span className="font-medium">{mergeSource?.breadcrumb}</span> will be moved
          to the selected location (recorded in stock movements), and this location will then be
          deleted.
        </p>
        <FormField
          as="select"
          label="Target Location *"
          value={mergeTarget}
          onChange={(e) => setMergeTarget(e.target.value ? Number(e.target.value) : '')}
        >
          <option value="">— Select a location —</option>
          {mergeTargets.map((loc) => (
            <option key={loc.id} value={loc.id}>
              {loc.breadcrumb}
            </option>
          ))}
        </FormField>
        {mergeError && <p className="mb-3 text-sm text-red-600">{mergeError}</p>}
        <div className="flex justify-end gap-3">
          <button
            onClick={() => setMergeSource(null)}
            className="rounded-lg border border-gray-300 px-4 py-2 text-sm hover:bg-gray-50"
          >
            Cancel
          </button>
          <button
            onClick={handleMerge}
            disabled={merging || !mergeTarget}
            className="rounded-lg bg-amber-600 px-4 py-2 text-sm font-medium text-white hover:bg-amber-700 disabled:opacity-50"
          >
            {merging ? 'Merging…' : 'Merge'}
          </button>
        </div>
      </Modal>
    </div>
  );
}
