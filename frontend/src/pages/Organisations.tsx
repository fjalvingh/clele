import { useEffect, useState } from 'react';
import {
  createOrganisation,
  deleteOrganisation,
  getOrganisations,
  updateOrganisation,
} from '../api';
import { type Organisation, type OrganisationRequest } from '../api/types';
import DataTable from '../components/DataTable';
import type { Column } from '../components/DataTable';
import FormField from '../components/FormField';
import Modal from '../components/Modal';

const emptyForm = (): OrganisationRequest => ({ name: '', description: '' });

export default function OrganisationsPage() {
  const [organisations, setOrganisations] = useState<Organisation[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [modalOpen, setModalOpen] = useState(false);
  const [editing, setEditing] = useState<Organisation | null>(null);
  const [form, setForm] = useState<OrganisationRequest>(emptyForm());
  const [saving, setSaving] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);

  const load = () => {
    setLoading(true);
    getOrganisations()
      .then(setOrganisations)
      .catch((e: Error) => setError(e.message))
      .finally(() => setLoading(false));
  };

  useEffect(load, []);

  const openCreate = () => {
    setEditing(null);
    setForm(emptyForm());
    setFormError(null);
    setModalOpen(true);
  };

  const openEdit = (org: Organisation) => {
    setEditing(org);
    setForm({ name: org.name, description: org.description ?? '' });
    setFormError(null);
    setModalOpen(true);
  };

  const handleSave = async () => {
    setSaving(true);
    setFormError(null);
    try {
      if (editing) {
        await updateOrganisation(editing.id, form);
      } else {
        await createOrganisation(form);
      }
      setModalOpen(false);
      load();
    } catch (e: unknown) {
      setFormError((e as Error).message);
    } finally {
      setSaving(false);
    }
  };

  const handleDelete = async (org: Organisation) => {
    if (!confirm(`Delete organisation "${org.name}"?`)) return;
    try {
      await deleteOrganisation(org.id);
      load();
    } catch (e: unknown) {
      alert((e as Error).message);
    }
  };

  const columns: Column<Organisation>[] = [
    {
      key: 'name',
      header: 'Name',
      render: (org) => (
        <span className="flex items-center gap-2">
          {org.name}
          {org.template && (
            <span className="rounded bg-gray-100 px-1.5 py-0.5 text-[10px] uppercase tracking-wide text-gray-500">
              Template
            </span>
          )}
        </span>
      ),
    },
    { key: 'description', header: 'Description', render: (org) => org.description || '—' },
  ];

  return (
    <div className="p-4 md:p-8">
      <div className="mb-2 flex items-center justify-between">
        <h1 className="text-2xl font-bold text-gray-900">Organisations</h1>
        <button
          onClick={openCreate}
          className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700"
        >
          + New Organisation
        </button>
      </div>
      <p className="mb-6 text-sm text-gray-600">
        Each organisation has its own, completely independent parts, stock, locations, categories,
        spec fields and tags. A new organisation starts as a copy of the{' '}
        <strong>Template</strong> organisation&rsquo;s categories, spec fields and tags — edit the
        template to change what new organisations begin with.
      </p>

      {loading && <p className="text-gray-500">Loading...</p>}
      {error && <p className="text-red-600">{error}</p>}

      {!loading && (
        <DataTable
          columns={columns}
          data={organisations}
          keyExtractor={(org) => org.id}
          actions={(org) => (
            <div className="flex justify-end gap-2">
              <button
                onClick={() => openEdit(org)}
                className="rounded px-2 py-1 text-xs text-blue-600 hover:bg-blue-50"
              >
                Edit
              </button>
              {!org.template && (
                <button
                  onClick={() => handleDelete(org)}
                  className="rounded px-2 py-1 text-xs text-red-600 hover:bg-red-50"
                  title="Only an organisation without parts, locations or projects can be deleted"
                >
                  Delete
                </button>
              )}
            </div>
          )}
        />
      )}

      <Modal
        open={modalOpen}
        onClose={() => setModalOpen(false)}
        title={editing ? 'Edit Organisation' : 'New Organisation'}
      >
        <FormField
          label="Name *"
          value={form.name}
          onChange={(e) => setForm({ ...form, name: e.target.value })}
          placeholder="e.g. Acme Electronics"
        />
        <FormField
          label="Description"
          value={form.description ?? ''}
          onChange={(e) => setForm({ ...form, description: e.target.value })}
        />

        {!editing && (
          <p className="mb-4 text-sm text-gray-600">
            The new organisation will start with a copy of the Template organisation&rsquo;s
            categories, spec fields and tags. It has no parts, locations or stock.
          </p>
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
