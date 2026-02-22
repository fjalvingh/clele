import { useEffect, useState } from 'react';
import {
  createLocation,
  deleteLocation,
  getLocations,
  updateLocation,
} from '../api';
import type { Location, LocationRequest } from '../api/types';
import DataTable from '../components/DataTable';
import type { Column } from '../components/DataTable';
import FormField from '../components/FormField';
import Modal from '../components/Modal';

export default function LocationsPage() {
  const [locations, setLocations] = useState<Location[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [modalOpen, setModalOpen] = useState(false);
  const [editing, setEditing] = useState<Location | null>(null);
  const [form, setForm] = useState<LocationRequest>({ name: '', description: '' });
  const [saving, setSaving] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);

  const load = () => {
    setLoading(true);
    getLocations()
      .then(setLocations)
      .catch((e: Error) => setError(e.message))
      .finally(() => setLoading(false));
  };

  useEffect(load, []);

  const openCreate = () => {
    setEditing(null);
    setForm({ name: '', description: '' });
    setFormError(null);
    setModalOpen(true);
  };

  const openEdit = (loc: Location) => {
    setEditing(loc);
    setForm({ name: loc.name, description: loc.description ?? '' });
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

  const columns: Column<Location>[] = [
    { key: 'name', header: 'Name' },
    {
      key: 'description',
      header: 'Description',
      render: (row) => row.description ?? '—',
    },
  ];

  return (
    <div className="p-8">
      <div className="mb-6 flex items-center justify-between">
        <h1 className="text-2xl font-bold text-gray-900">Locations</h1>
        <button
          onClick={openCreate}
          className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700"
        >
          + New Location
        </button>
      </div>

      {loading && <p className="text-gray-500">Loading...</p>}
      {error && <p className="text-red-600">{error}</p>}

      {!loading && (
        <DataTable
          columns={columns}
          data={locations}
          keyExtractor={(l) => l.id}
          actions={(loc) => (
            <div className="flex justify-end gap-2">
              <button
                onClick={() => openEdit(loc)}
                className="rounded px-2 py-1 text-xs text-blue-600 hover:bg-blue-50"
              >
                Edit
              </button>
              <button
                onClick={() => handleDelete(loc)}
                className="rounded px-2 py-1 text-xs text-red-600 hover:bg-red-50"
              >
                Delete
              </button>
            </div>
          )}
        />
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
          placeholder="e.g. Bin A3"
        />
        <FormField
          as="textarea"
          label="Description"
          value={form.description}
          onChange={(e) => setForm({ ...form, description: e.target.value })}
          rows={2}
          placeholder="Optional description"
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
