import { useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import {
  createSpecGroup,
  deleteSpecGroup,
  getSpecGroups,
  rescanSpecDefinitions,
  updateSpecGroup,
} from '../api';
import type { SpecGroup, SpecGroupRequest } from '../api/types';
import FormField from '../components/FormField';
import Modal from '../components/Modal';

const emptyForm = (order: number): SpecGroupRequest => ({
  name: '',
  description: '',
  displayOrder: order,
});

/**
 * Spec Fields — the group overview. Individual fields live one level down, inside their group
 * (see SpecGroupDetail): a flat list of several hundred fields was the thing that made these
 * impossible to edit.
 */
export default function SpecDefinitionsPage() {
  const navigate = useNavigate();
  const [groups, setGroups] = useState<SpecGroup[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [modalOpen, setModalOpen] = useState(false);
  const [editing, setEditing] = useState<SpecGroup | null>(null);
  const [form, setForm] = useState<SpecGroupRequest>(emptyForm(0));
  const [saving, setSaving] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);
  const [rescanning, setRescanning] = useState(false);

  const load = () => {
    setLoading(true);
    getSpecGroups()
      .then(setGroups)
      .catch((e: Error) => setError(e.message))
      .finally(() => setLoading(false));
  };

  useEffect(load, []);

  const openCreate = () => {
    setEditing(null);
    // Land new groups at the end of the current order rather than colliding at 0.
    setForm(emptyForm(groups.reduce((max, g) => Math.max(max, g.displayOrder), -1) + 1));
    setFormError(null);
    setModalOpen(true);
  };

  const openEdit = (group: SpecGroup) => {
    setEditing(group);
    setForm({
      name: group.name,
      description: group.description ?? '',
      displayOrder: group.displayOrder,
    });
    setFormError(null);
    setModalOpen(true);
  };

  const handleSave = async () => {
    setSaving(true);
    setFormError(null);
    try {
      if (editing) {
        await updateSpecGroup(editing.id, form);
      } else {
        await createSpecGroup(form);
      }
      setModalOpen(false);
      load();
    } catch (e: unknown) {
      setFormError((e as Error).message);
    } finally {
      setSaving(false);
    }
  };

  const handleDelete = async (group: SpecGroup) => {
    if (!confirm(`Delete spec group "${group.name}"?`)) return;
    try {
      await deleteSpecGroup(group.id);
      load();
    } catch (e: unknown) {
      alert((e as Error).message);
    }
  };

  const handleRescan = async () => {
    if (
      !confirm(
        'Scan all parts and upsert spec fields from their specs? ' +
          'Inferred type and options are refreshed; titles and units you edited are kept.'
      )
    )
      return;
    setRescanning(true);
    setError(null);
    try {
      await rescanSpecDefinitions();
      load();
    } catch (e: unknown) {
      setError((e as Error).message);
    } finally {
      setRescanning(false);
    }
  };

  return (
    <div className="p-8">
      <div className="mb-6 flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-gray-900">Spec Fields</h1>
          <p className="mt-1 text-sm text-gray-500">
            Specification fields are organised in groups of related fields. Open a group to add,
            edit, merge or move its fields.
          </p>
        </div>
        <div className="flex gap-3">
          <button
            onClick={handleRescan}
            disabled={rescanning}
            className="rounded-lg border border-gray-300 px-4 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50 disabled:opacity-50"
          >
            {rescanning ? 'Rescanning…' : 'Rescan from parts'}
          </button>
          <button
            onClick={openCreate}
            className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700"
          >
            + New Group
          </button>
        </div>
      </div>

      {loading && <p className="text-gray-500">Loading...</p>}
      {error && <p className="text-red-600">{error}</p>}

      {!loading && (
        <div className="overflow-hidden rounded-xl border border-gray-200 bg-surface shadow-sm">
          {groups.length === 0 ? (
            <p className="p-6 text-sm text-gray-400">
              No spec groups yet. Create one to get started.
            </p>
          ) : (
            <table className="min-w-full divide-y divide-gray-200 text-sm">
              <thead className="bg-gray-50">
                <tr>
                  <th className="px-4 py-3 text-left font-medium text-gray-500">Group</th>
                  <th className="px-4 py-3 text-left font-medium text-gray-500">Description</th>
                  <th className="px-4 py-3 text-left font-medium text-gray-500">Fields</th>
                  <th className="px-4 py-3 text-left font-medium text-gray-500">Order</th>
                  <th className="px-4 py-3" />
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-100">
                {groups.map((group) => (
                  <tr
                    key={group.id}
                    onClick={() => navigate(`/specs/${group.id}`)}
                    className="cursor-pointer hover:bg-gray-50"
                  >
                    <td className="px-4 py-3 font-medium text-gray-900">
                      <Link
                        to={`/specs/${group.id}`}
                        onClick={(e) => e.stopPropagation()}
                        className="hover:underline"
                      >
                        {group.name}
                      </Link>
                    </td>
                    <td className="px-4 py-3 text-gray-600">{group.description || '—'}</td>
                    <td className="px-4 py-3 text-gray-600">{group.specCount}</td>
                    <td className="px-4 py-3 text-gray-500">{group.displayOrder}</td>
                    <td className="px-4 py-3" onClick={(e) => e.stopPropagation()}>
                      <div className="flex justify-end gap-2">
                        <button
                          onClick={() => openEdit(group)}
                          className="rounded px-2 py-1 text-xs text-blue-600 hover:bg-blue-50"
                        >
                          Edit
                        </button>
                        <button
                          onClick={() => handleDelete(group)}
                          className="rounded px-2 py-1 text-xs text-red-600 hover:bg-red-50"
                        >
                          Delete
                        </button>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      )}

      <Modal
        open={modalOpen}
        onClose={() => setModalOpen(false)}
        title={editing ? 'Edit Spec Group' : 'New Spec Group'}
      >
        <FormField
          label="Name *"
          value={form.name}
          onChange={(e) => setForm({ ...form, name: e.target.value })}
          placeholder="e.g. Power, MCU Specs"
        />
        <FormField
          label="Description"
          value={form.description ?? ''}
          onChange={(e) => setForm({ ...form, description: e.target.value })}
          placeholder="What this group covers"
        />
        <FormField
          label="Display Order"
          type="number"
          min={0}
          value={form.displayOrder}
          onChange={(e) => setForm({ ...form, displayOrder: Number(e.target.value) })}
        />

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
