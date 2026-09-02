import { useEffect, useState } from 'react';
import { getCategoryTree, updatePart } from '../api';
import type { CategoryTree, Part, PartRequest } from '../api/types';
import CategoryPicker from './CategoryPicker';
import FormField from './FormField';
import Modal from './Modal';
import PartSpecEditor from './PartSpecEditor';
import TagInput from './TagInput';

interface Props {
  open: boolean;
  part: Part;
  onClose: () => void;
  onSaved: (updated: Part) => void;
}

export default function PartEditModal({ open, part, onClose, onSaved }: Props) {
  const [categoryTree, setCategoryTree] = useState<CategoryTree[]>([]);
  const [form, setForm] = useState<PartRequest>({
    partNumber: '',
    description: '',
    details: '',
    manufacturer: '',
    personalNumber: false,
    datasheetUrl: '',
    specs: {},
    categoryId: null,
    tags: [],
  });
  const [specValues, setSpecValues] = useState<Record<string, string>>({});
  // Everything the part arrived with stays on the form even once emptied — see PartSpecEditor.
  const [shownKeys, setShownKeys] = useState<string[]>([]);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Load category tree once
  useEffect(() => {
    getCategoryTree().then(setCategoryTree).catch(() => {});
  }, []);

  // Populate form when the modal opens
  useEffect(() => {
    if (!open) return;
    setForm({
      partNumber: part.partNumber,
      description: part.description ?? '',
      details: part.details ?? '',
      manufacturer: part.manufacturer ?? '',
      personalNumber: part.personalNumber ?? false,
      datasheetUrl: part.datasheetUrl ?? '',
      specs: part.specs ?? {},
      categoryId: part.categoryId ?? null,
      tags: part.tags ?? [],
    });
    const existing: Record<string, string> = {};
    for (const [k, v] of Object.entries(part.specs ?? {})) {
      existing[k] = String(v);
    }
    setSpecValues(existing);
    setShownKeys(Object.keys(existing));
    setError(null);
  }, [open, part]);

  const handleSave = async () => {
    setSaving(true);
    setError(null);
    // Save every non-empty value, not just the ones a definition covers — dropping the rest would
    // silently discard specs imported under keys nobody has defined yet.
    const filteredSpecs: Record<string, string> = {};
    for (const [key, value] of Object.entries(specValues)) {
      if (value !== undefined && value !== '') filteredSpecs[key] = value;
    }
    try {
      // REPLACE is correct here and only here: this form renders every key the part carries,
      // including the ones under "Other" that no definition covers. That is also what makes the
      // per-row remove button work — under the default MERGE an omitted key means "leave alone",
      // so a removed row would come back on reload.
      const updated = await updatePart(part.id, {
        ...form,
        specs: filteredSpecs,
        specsMode: 'REPLACE',
      });
      onSaved(updated);
    } catch (e: unknown) {
      setError((e as Error).message);
    } finally {
      setSaving(false);
    }
  };

  return (
    <Modal open={open} onClose={onClose} title="Edit Part">
      <div className="max-h-[70vh] overflow-y-auto pr-1">
        <FormField
          label="Part Number *"
          value={form.partNumber}
          onChange={(e) => setForm({ ...form, partNumber: e.target.value })}
          placeholder="e.g. BC547"
        />
        <label className="mb-3 flex items-center gap-2 text-sm text-gray-700 dark:text-gray-300">
          <input
            type="checkbox"
            checked={form.personalNumber ?? false}
            onChange={(e) => setForm({ ...form, personalNumber: e.target.checked })}
          />
          This is a personal/internal number, not a real manufacturer part number
        </label>
        <FormField
          label="Manufacturer"
          value={form.manufacturer ?? ''}
          onChange={(e) => setForm({ ...form, manufacturer: e.target.value })}
        />
        <FormField
          as="textarea"
          label="Description"
          value={form.description ?? ''}
          onChange={(e) => setForm({ ...form, description: e.target.value })}
          rows={2}
        />
        <FormField
          as="textarea"
          label="Details"
          value={form.details ?? ''}
          onChange={(e) => setForm({ ...form, details: e.target.value })}
          rows={4}
        />
        <FormField
          label="Datasheet URL"
          value={form.datasheetUrl ?? ''}
          onChange={(e) => setForm({ ...form, datasheetUrl: e.target.value })}
          type="url"
        />
        <CategoryPicker
          label="Category"
          categories={categoryTree}
          value={form.categoryId ?? null}
          onChange={(id) => setForm({ ...form, categoryId: id })}
        />
        <TagInput
          value={form.tags ?? []}
          onChange={(tags) => setForm({ ...form, tags })}
        />

        <PartSpecEditor
          values={specValues}
          onValuesChange={setSpecValues}
          shownKeys={shownKeys}
          onShownKeysChange={setShownKeys}
          emptyText="This part has no specifications yet. Search below to add one."
        />
      </div>
      {error && <p className="mb-3 text-sm text-red-600">{error}</p>}
      <div className="flex justify-end gap-3 pt-2">
        <button
          onClick={onClose}
          className="rounded-lg border border-gray-300 px-4 py-2 text-sm hover:bg-gray-50"
        >
          Cancel
        </button>
        <button
          onClick={handleSave}
          disabled={saving || !form.partNumber.trim()}
          className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-50"
        >
          {saving ? 'Saving…' : 'Save'}
        </button>
      </div>
    </Modal>
  );
}
