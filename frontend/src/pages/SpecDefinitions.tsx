import { useEffect, useState } from 'react';
import {
  createSpecDefinition,
  deleteSpecDefinition,
  getSpecDefinitions,
  updateSpecDefinition,
} from '../api';
import type { SpecDefinition, SpecDefinitionRequest } from '../api/types';
import FormField from '../components/FormField';
import Modal from '../components/Modal';

const DATA_TYPES = ['TEXT', 'NUMBER', 'BOOLEAN', 'SELECT'] as const;

const emptyForm = (): SpecDefinitionRequest => ({
  name: '',
  dataType: 'TEXT',
  unit: '',
  options: [],
  displayOrder: 0,
});

function typeLabel(dataType: string): string {
  switch (dataType) {
    case 'TEXT': return 'Text';
    case 'NUMBER': return 'Number';
    case 'BOOLEAN': return 'Boolean';
    case 'SELECT': return 'Select';
    default: return dataType;
  }
}

function unitOrOptions(spec: SpecDefinition): string {
  if (spec.dataType === 'NUMBER' && spec.unit) return spec.unit;
  if (spec.dataType === 'SELECT' && spec.options && spec.options.length > 0)
    return spec.options.join(', ');
  return '—';
}

export default function SpecDefinitionsPage() {
  const [specs, setSpecs] = useState<SpecDefinition[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [modalOpen, setModalOpen] = useState(false);
  const [editing, setEditing] = useState<SpecDefinition | null>(null);
  const [form, setForm] = useState<SpecDefinitionRequest>(emptyForm());
  const [optionsText, setOptionsText] = useState('');
  const [saving, setSaving] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);

  const load = () => {
    setLoading(true);
    getSpecDefinitions()
      .then(setSpecs)
      .catch((e: Error) => setError(e.message))
      .finally(() => setLoading(false));
  };

  useEffect(load, []);

  const openCreate = () => {
    setEditing(null);
    setForm(emptyForm());
    setOptionsText('');
    setFormError(null);
    setModalOpen(true);
  };

  const openEdit = (spec: SpecDefinition) => {
    setEditing(spec);
    setForm({
      name: spec.name,
      dataType: spec.dataType,
      unit: spec.unit ?? '',
      options: spec.options ?? [],
      displayOrder: spec.displayOrder,
    });
    setOptionsText(spec.options ? spec.options.join(', ') : '');
    setFormError(null);
    setModalOpen(true);
  };

  const handleSave = async () => {
    setSaving(true);
    setFormError(null);

    const parsedOptions =
      form.dataType === 'SELECT'
        ? optionsText.split(',').map((s) => s.trim()).filter(Boolean)
        : [];

    const payload: SpecDefinitionRequest = {
      ...form,
      unit: form.dataType === 'NUMBER' ? (form.unit ?? '') : '',
      options: parsedOptions,
    };

    try {
      if (editing) {
        await updateSpecDefinition(editing.id, payload);
      } else {
        await createSpecDefinition(payload);
      }
      setModalOpen(false);
      load();
    } catch (e: unknown) {
      setFormError((e as Error).message);
    } finally {
      setSaving(false);
    }
  };

  const handleDelete = async (spec: SpecDefinition) => {
    if (!confirm(`Delete spec field "${spec.name}"?`)) return;
    try {
      await deleteSpecDefinition(spec.id);
      load();
    } catch (e: unknown) {
      alert((e as Error).message);
    }
  };

  return (
    <div className="p-8">
      <div className="mb-6 flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-gray-900">Spec Fields</h1>
          <p className="mt-1 text-sm text-gray-500">
            Define typed specification fields that can be assigned to categories.
          </p>
        </div>
        <button
          onClick={openCreate}
          className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700"
        >
          + New Spec Field
        </button>
      </div>

      {loading && <p className="text-gray-500">Loading...</p>}
      {error && <p className="text-red-600">{error}</p>}

      {!loading && (
        <div className="rounded-xl border border-gray-200 bg-white shadow-sm overflow-hidden">
          {specs.length === 0 ? (
            <p className="p-6 text-sm text-gray-400">
              No spec fields defined yet. Create one to get started.
            </p>
          ) : (
            <table className="min-w-full divide-y divide-gray-200 text-sm">
              <thead className="bg-gray-50">
                <tr>
                  <th className="px-4 py-3 text-left font-medium text-gray-500">Name</th>
                  <th className="px-4 py-3 text-left font-medium text-gray-500">Type</th>
                  <th className="px-4 py-3 text-left font-medium text-gray-500">Unit / Options</th>
                  <th className="px-4 py-3 text-left font-medium text-gray-500">Order</th>
                  <th className="px-4 py-3" />
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-100">
                {specs.map((spec) => (
                  <tr key={spec.id} className="hover:bg-gray-50">
                    <td className="px-4 py-3 font-medium text-gray-900">{spec.name}</td>
                    <td className="px-4 py-3">
                      <span className="rounded-full bg-blue-100 px-2 py-0.5 text-xs text-blue-700">
                        {typeLabel(spec.dataType)}
                      </span>
                    </td>
                    <td className="px-4 py-3 text-gray-600">{unitOrOptions(spec)}</td>
                    <td className="px-4 py-3 text-gray-500">{spec.displayOrder}</td>
                    <td className="px-4 py-3">
                      <div className="flex justify-end gap-2">
                        <button
                          onClick={() => openEdit(spec)}
                          className="rounded px-2 py-1 text-xs text-blue-600 hover:bg-blue-50"
                        >
                          Edit
                        </button>
                        <button
                          onClick={() => handleDelete(spec)}
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
        title={editing ? 'Edit Spec Field' : 'New Spec Field'}
      >
        <FormField
          label="Name *"
          value={form.name}
          onChange={(e) => setForm({ ...form, name: e.target.value })}
          placeholder="e.g. Package, Voltage Rating"
        />
        <FormField
          as="select"
          label="Type *"
          value={form.dataType}
          onChange={(e) => setForm({ ...form, dataType: e.target.value })}
        >
          {DATA_TYPES.map((t) => (
            <option key={t} value={t}>
              {typeLabel(t)}
            </option>
          ))}
        </FormField>

        {form.dataType === 'NUMBER' && (
          <FormField
            label="Unit — or comma-separated list for a selector"
            value={form.unit ?? ''}
            onChange={(e) => setForm({ ...form, unit: e.target.value })}
            placeholder="e.g. V  or  B,KB,MB,GB"
          />
        )}

        {form.dataType === 'SELECT' && (
          <div className="mb-4">
            <label className="block text-sm font-medium text-gray-700">
              Options (comma-separated)
            </label>
            <textarea
              className="mt-1 block w-full rounded-md border border-gray-300 px-3 py-2 text-sm shadow-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
              rows={3}
              value={optionsText}
              onChange={(e) => setOptionsText(e.target.value)}
              placeholder="e.g. DIP-8, SOIC-8, SOT-23"
            />
          </div>
        )}

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
