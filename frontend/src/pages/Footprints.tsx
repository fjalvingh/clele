import { useEffect, useMemo, useState } from 'react';
import { createFootprint, deleteFootprint, getFootprints, mergeFootprint, updateFootprint } from '../api';
import type { Footprint } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import FormField from '../components/FormField';
import Modal from '../components/Modal';
import { footprintKey, invalidateFootprints } from '../utils/footprints';

interface EditForm {
  name: string;
  description: string;
  /** One alias per line. */
  aliases: string;
}

const emptyForm: EditForm = { name: '', description: '', aliases: '' };

/**
 * The organisation's footprint list. Every part's footprint is a name from it; a spelling that
 * differs only in case or punctuation is the same footprint, and an alias covers what that cannot
 * (TO-236 is SOT-23). Renaming moves the parts along, and merging is how two names for one package
 * become one.
 */
export default function FootprintsPage() {
  const { hasPermission } = useAuth();
  const canEdit = hasPermission('PARTS_EDIT');

  const [footprints, setFootprints] = useState<Footprint[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [filter, setFilter] = useState('');
  const [usedOnly, setUsedOnly] = useState(false);

  const [editing, setEditing] = useState<Footprint | null>(null);
  const [editOpen, setEditOpen] = useState(false);
  const [form, setForm] = useState<EditForm>(emptyForm);
  const [merging, setMerging] = useState<Footprint | null>(null);
  const [mergeTarget, setMergeTarget] = useState<number | null>(null);
  const [saving, setSaving] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);

  const load = () => {
    setLoading(true);
    getFootprints()
      .then(setFootprints)
      .catch((e: Error) => setError(e.message))
      .finally(() => setLoading(false));
  };

  useEffect(load, []);

  const changed = () => {
    invalidateFootprints();
    load();
  };

  const shown = useMemo(() => {
    const key = filter.trim() ? footprintKey(filter) : null;
    return footprints
      .filter((f) => !usedOnly || f.partCount > 0)
      .filter(
        (f) =>
          key === null ||
          footprintKey(f.name).includes(key) ||
          f.aliases.some((a) => footprintKey(a).includes(key)),
      );
  }, [footprints, filter, usedOnly]);

  const openCreate = () => {
    setEditing(null);
    setForm(emptyForm);
    setFormError(null);
    setEditOpen(true);
  };

  const openEdit = (f: Footprint) => {
    setEditing(f);
    setForm({ name: f.name, description: f.description ?? '', aliases: f.aliases.join('\n') });
    setFormError(null);
    setEditOpen(true);
  };

  const handleSave = async () => {
    setSaving(true);
    setFormError(null);
    const request = {
      name: form.name.trim(),
      description: form.description.trim() || null,
      aliases: form.aliases.split('\n').map((a) => a.trim()).filter(Boolean),
    };
    try {
      if (editing) {
        await updateFootprint(editing.id, request);
      } else {
        await createFootprint(request);
      }
      setEditOpen(false);
      changed();
    } catch (e: unknown) {
      setFormError((e as Error).message);
    } finally {
      setSaving(false);
    }
  };

  const openMerge = (f: Footprint) => {
    setMerging(f);
    setMergeTarget(null);
    setFormError(null);
  };

  const handleMerge = async () => {
    if (!merging || mergeTarget === null) return;
    setSaving(true);
    setFormError(null);
    try {
      await mergeFootprint(merging.id, mergeTarget);
      setMerging(null);
      changed();
    } catch (e: unknown) {
      setFormError((e as Error).message);
    } finally {
      setSaving(false);
    }
  };

  const handleDelete = async (f: Footprint) => {
    if (!confirm(`Delete footprint "${f.name}"?`)) return;
    try {
      await deleteFootprint(f.id);
      changed();
    } catch (e: unknown) {
      alert((e as Error).message);
    }
  };

  const renaming = editing !== null && form.name.trim() !== editing.name && editing.partCount > 0;

  return (
    <div className="p-4 md:p-8">
      <div className="mb-2 flex items-center justify-between gap-4">
        <h1 className="text-2xl font-bold text-gray-900">Footprints</h1>
        {canEdit && (
          <button
            onClick={openCreate}
            className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700"
          >
            + New Footprint
          </button>
        )}
      </div>
      <p className="mb-6 max-w-3xl text-sm text-gray-500">
        The package types parts can have. Case and punctuation do not count, so SOT23 and sot-23 are
        both SOT-23; an alias covers a name that differs more (TO-236 for SOT-23). Two entries that
        are the same package can be merged.
      </p>

      <div className="mb-4 flex flex-wrap items-center gap-4">
        <input
          value={filter}
          onChange={(e) => setFilter(e.target.value)}
          placeholder="Filter by name or alias"
          className="w-64 rounded-lg border border-gray-300 px-3 py-2 text-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
        />
        <label className="flex items-center gap-2 text-sm text-gray-700">
          <input type="checkbox" checked={usedOnly} onChange={(e) => setUsedOnly(e.target.checked)} />
          Only footprints in use
        </label>
        <span className="text-sm text-gray-400">
          {shown.length} of {footprints.length}
        </span>
      </div>

      {loading && <p className="text-gray-500">Loading...</p>}
      {error && <p className="text-red-600">{error}</p>}

      {!loading && (
        <div className="overflow-x-auto rounded-xl border border-gray-200 bg-surface shadow-sm">
          <table className="min-w-full text-sm">
            <thead className="border-b border-gray-200 text-left text-xs uppercase tracking-wide text-gray-500">
              <tr>
                <th className="px-4 py-2">Name</th>
                <th className="px-4 py-2">Also known as</th>
                <th className="px-4 py-2">Description</th>
                <th className="px-4 py-2 text-right">Parts</th>
                {canEdit && <th className="px-4 py-2" />}
              </tr>
            </thead>
            <tbody>
              {shown.map((f) => (
                <tr key={f.id} className="group border-b border-gray-100 last:border-0 hover:bg-gray-50">
                  <td className="px-4 py-2 font-medium text-gray-900">{f.name}</td>
                  <td className="px-4 py-2 text-gray-500">{f.aliases.join(', ')}</td>
                  <td className="px-4 py-2 text-gray-500">{f.description}</td>
                  <td className="px-4 py-2 text-right">
                    <span className={f.partCount > 0 ? 'text-gray-700' : 'text-gray-300'}>{f.partCount}</span>
                  </td>
                  {canEdit && (
                    <td className="whitespace-nowrap px-4 py-2 text-right">
                      <div className="flex justify-end gap-1 opacity-0 transition-opacity group-hover:opacity-100">
                        <button
                          onClick={() => openEdit(f)}
                          className="rounded px-2 py-0.5 text-xs text-blue-600 hover:bg-blue-50"
                        >
                          Edit
                        </button>
                        <button
                          onClick={() => openMerge(f)}
                          className="rounded px-2 py-0.5 text-xs text-blue-600 hover:bg-blue-50"
                        >
                          Merge into…
                        </button>
                        {f.partCount === 0 && (
                          <button
                            onClick={() => handleDelete(f)}
                            className="rounded px-2 py-0.5 text-xs text-red-600 hover:bg-red-50"
                          >
                            Delete
                          </button>
                        )}
                      </div>
                    </td>
                  )}
                </tr>
              ))}
              {shown.length === 0 && (
                <tr>
                  <td colSpan={5} className="px-4 py-6 text-center text-gray-400">
                    No footprints match.
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      )}

      <Modal
        open={editOpen}
        onClose={() => setEditOpen(false)}
        title={editing ? `Edit ${editing.name}` : 'New Footprint'}
      >
        <FormField
          label="Name *"
          value={form.name}
          onChange={(e) => setForm({ ...form, name: e.target.value })}
          placeholder="e.g. SOT-23-5"
          maxLength={64}
        />
        {renaming && (
          <p className="-mt-3 mb-4 text-xs text-gray-500">
            The {editing.partCount} part{editing.partCount === 1 ? '' : 's'} using {editing.name} move
            along, and {editing.name} stays as an alias.
          </p>
        )}
        <FormField
          as="textarea"
          label="Also known as (one per line)"
          value={form.aliases}
          onChange={(e) => setForm({ ...form, aliases: e.target.value })}
          rows={4}
          placeholder={'e.g. SOT-25\nSC-74A'}
        />
        <FormField
          as="textarea"
          label="Description"
          value={form.description}
          onChange={(e) => setForm({ ...form, description: e.target.value })}
          rows={2}
        />
        {formError && <p className="mb-3 text-sm text-red-600">{formError}</p>}
        <div className="flex justify-end gap-3">
          <button
            onClick={() => setEditOpen(false)}
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

      <Modal open={merging !== null} onClose={() => setMerging(null)} title={`Merge ${merging?.name ?? ''}`}>
        {merging && (
          <>
            <p className="mb-4 text-sm text-gray-600">
              {merging.partCount > 0
                ? `The ${merging.partCount} part${merging.partCount === 1 ? '' : 's'} using ${merging.name} move to the footprint you pick. `
                : ''}
              {merging.name}
              {merging.aliases.length > 0 ? ' and its aliases become aliases' : ' becomes an alias'} of
              it, and {merging.name} disappears from the list.
            </p>
            <FormField
              as="select"
              label="Merge into"
              value={mergeTarget ?? ''}
              onChange={(e) => setMergeTarget(e.target.value ? Number(e.target.value) : null)}
            >
              <option value="">— Choose a footprint —</option>
              {footprints
                .filter((f) => f.id !== merging.id)
                .map((f) => (
                  <option key={f.id} value={f.id}>
                    {f.name}
                    {f.partCount > 0 ? ` (${f.partCount})` : ''}
                  </option>
                ))}
            </FormField>
            {formError && <p className="mb-3 text-sm text-red-600">{formError}</p>}
            <div className="flex justify-end gap-3">
              <button
                onClick={() => setMerging(null)}
                className="rounded-lg border border-gray-300 px-4 py-2 text-sm hover:bg-gray-50"
              >
                Cancel
              </button>
              <button
                onClick={handleMerge}
                disabled={saving || mergeTarget === null}
                className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-50"
              >
                {saving ? 'Merging…' : 'Merge'}
              </button>
            </div>
          </>
        )}
      </Modal>
    </div>
  );
}
