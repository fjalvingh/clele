import { useEffect, useState } from 'react';
import {
  createLocation,
  deleteLocation,
  getLocations,
  getLocationTree,
  getUsers,
  updateLocation,
} from '../api';
import type { Location, LocationRequest, LocationTree, User } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import FormField from '../components/FormField';
import Modal from '../components/Modal';

// ---- Tree node component ----
interface TreeNodeProps {
  node: LocationTree;
  onEdit: (loc: Location) => void;
  onDelete: (loc: Location) => void;
  onAddChild: (parentId: number) => void;
  canManage: (loc: Location) => boolean;
  locations: Location[];
}

function TreeNode({ node, onEdit, onDelete, onAddChild, canManage, locations }: TreeNodeProps) {
  const [expanded, setExpanded] = useState(true);
  const hasChildren = node.children && node.children.length > 0;
  const fullLoc = locations.find((l) => l.id === node.id);
  const manageable = fullLoc ? canManage(fullLoc) : false;

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
        {node.ownerName && (
          <span className="rounded bg-gray-100 px-1.5 py-0.5 text-xs text-gray-500">
            {node.ownerName}
          </span>
        )}
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
              onClick={() => onDelete(fullLoc)}
              className="rounded px-2 py-0.5 text-xs text-red-600 hover:bg-red-50"
            >
              Delete
            </button>
          )}
        </div>
      </div>
      {hasChildren && expanded && (
        <div className="border-l border-gray-200 ml-2">
          {node.children.map((child) => (
            <TreeNode
              key={child.id}
              node={child}
              onEdit={onEdit}
              onDelete={onDelete}
              onAddChild={onAddChild}
              canManage={canManage}
              locations={locations}
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

// Build an indented option list of candidate parents. Only locations owned by `ownerId` are
// eligible (the backend requires a location's parent to share its owner), and the location being
// edited together with its whole subtree is skipped (a node can't be its own ancestor).
function buildParentOptions(
  nodes: LocationTree[],
  excludeId: number | null,
  ownerId: number | undefined,
  depth = 0
): ParentOption[] {
  const options: ParentOption[] = [];
  for (const node of nodes) {
    if (node.id === excludeId) continue; // skip this node and its entire subtree
    if (node.ownerId === ownerId) {
      const prefix = depth > 0 ? '  '.repeat(depth) + '└ ' : '';
      options.push({ id: node.id, label: prefix + node.name });
    }
    options.push(...buildParentOptions(node.children, excludeId, ownerId, depth + 1));
  }
  return options;
}

const emptyForm: LocationRequest = { name: '', description: '', parentId: null };

export default function LocationsPage() {
  const { user, hasPermission } = useAuth();
  const isAdmin = hasPermission('USERS_EDIT');
  const canManage = (loc: Location) => isAdmin || loc.ownerId === user?.id;
  const [tree, setTree] = useState<LocationTree[]>([]);
  const [locations, setLocations] = useState<Location[]>([]);
  const [users, setUsers] = useState<User[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [modalOpen, setModalOpen] = useState(false);
  const [editing, setEditing] = useState<Location | null>(null);
  const [form, setForm] = useState<LocationRequest>(emptyForm);
  const [saving, setSaving] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);

  const load = () => {
    setLoading(true);
    Promise.all([getLocationTree(), getLocations()])
      .then(([t, l]) => {
        setTree(t);
        setLocations(l);
      })
      .catch((e: Error) => setError(e.message))
      .finally(() => setLoading(false));
    // Admins can reassign ownership, so they need the user list for the picker.
    if (isAdmin) {
      getUsers()
        .then(setUsers)
        .catch(() => setUsers([]));
    }
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
      ownerId: loc.ownerId,
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

  // Parent candidates: locations owned by the effective owner (the editing location's owner, which
  // an admin may reassign, or the current user when creating), minus the edited node's subtree.
  const effectiveOwnerId = editing ? form.ownerId : user?.id;
  const parentOptions = buildParentOptions(tree, editing?.id ?? null, effectiveOwnerId);

  return (
    <div className="p-8">
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
        <div className="rounded-xl border border-gray-200 bg-white p-4 shadow-sm">
          {tree.length === 0 ? (
            <p className="text-sm text-gray-400">No locations yet. Create one to get started.</p>
          ) : (
            tree.map((root) => (
              <TreeNode
                key={root.id}
                node={root}
                onEdit={openEdit}
                onDelete={handleDelete}
                onAddChild={(parentId) => openCreate(parentId)}
                canManage={canManage}
                locations={locations}
              />
            ))
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
        {isAdmin && editing && (
          <FormField
            as="select"
            label="Owner"
            value={form.ownerId ?? ''}
            onChange={(e) =>
              // Changing owner clears the parent: a parent must share the new owner.
              setForm({
                ...form,
                ownerId: e.target.value ? Number(e.target.value) : undefined,
                parentId: null,
              })
            }
          >
            {users.map((u) => (
              <option key={u.id} value={u.id}>
                {u.fullName || u.email}
              </option>
            ))}
          </FormField>
        )}
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
    </div>
  );
}
