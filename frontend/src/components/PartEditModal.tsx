import { useEffect, useState } from 'react';
import { getCategoryTree, getSpecsForCategory, updatePart } from '../api';
import type { CategoryTree, Part, PartRequest, SpecDefinition } from '../api/types';
import FormField from './FormField';
import MetricNumberField from './MetricNumberField';
import Modal from './Modal';

interface CatOption { id: number; label: string }

function buildCatOptions(nodes: CategoryTree[], depth = 0): CatOption[] {
  const opts: CatOption[] = [];
  for (const node of nodes) {
    const prefix = depth > 0 ? '  '.repeat(depth) + '└ ' : '';
    opts.push({ id: node.id, label: prefix + node.name });
    opts.push(...buildCatOptions(node.children, depth + 1));
  }
  return opts;
}

function parseMultiUnit(value: string, units: string[]): [string, string] {
  for (const u of units) {
    if (value.endsWith(' ' + u)) return [value.slice(0, -(u.length + 1)), u];
  }
  return [value, units[0] ?? ''];
}

function SpecField({
  spec,
  value,
  onChange,
}: {
  spec: SpecDefinition;
  value: string;
  onChange: (val: string) => void;
}) {
  if (spec.dataType === 'BOOLEAN') {
    return (
      <div className="mb-4">
        <label className="flex items-center gap-2 cursor-pointer">
          <input
            type="checkbox"
            checked={value === 'true'}
            onChange={(e) => onChange(e.target.checked ? 'true' : 'false')}
            className="rounded border-gray-300 text-blue-600"
          />
          <span className="text-sm font-medium text-gray-700">{spec.name}</span>
        </label>
      </div>
    );
  }

  if (spec.dataType === 'SELECT' && spec.options && spec.options.length > 0) {
    return (
      <div className="mb-4">
        <label className="block text-sm font-medium text-gray-700">{spec.name}</label>
        <select
          value={value}
          onChange={(e) => onChange(e.target.value)}
          className="mt-1 block w-full rounded-md border border-gray-300 px-3 py-2 text-sm shadow-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
        >
          <option value="">— Select —</option>
          {spec.options.map((opt) => (
            <option key={opt} value={opt}>{opt}</option>
          ))}
        </select>
      </div>
    );
  }

  if (spec.dataType === 'NUMBER') {
    const units = spec.unit ? spec.unit.split(',').map((s) => s.trim()) : [];
    const isMulti = units.length > 1;
    if (!isMulti && spec.metricPrefix && units[0]) {
      return (
        <MetricNumberField
          label={spec.name}
          unit={units[0]}
          value={value}
          onChange={onChange}
          inputClassName="block flex-1 rounded-md border border-gray-300 px-3 py-2 text-sm shadow-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
          selectClassName="rounded-md border border-gray-300 px-2 py-2 text-sm shadow-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
        />
      );
    }
    if (isMulti) {
      const [numPart, unitPart] = parseMultiUnit(value, units);
      return (
        <div className="mb-4">
          <label className="block text-sm font-medium text-gray-700">{spec.name}</label>
          <div className="mt-1 flex gap-2">
            <input
              type="number"
              step="any"
              value={numPart}
              onChange={(e) => onChange(e.target.value ? e.target.value + ' ' + unitPart : '')}
              className="block flex-1 rounded-md border border-gray-300 px-3 py-2 text-sm shadow-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
            />
            <select
              value={unitPart}
              onChange={(e) => onChange(numPart ? numPart + ' ' + e.target.value : '')}
              className="rounded-md border border-gray-300 px-2 py-2 text-sm shadow-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
            >
              {units.map((u) => <option key={u} value={u}>{u}</option>)}
            </select>
          </div>
        </div>
      );
    }
    return (
      <div className="mb-4">
        <label className="block text-sm font-medium text-gray-700">
          {spec.name}{units[0] ? ` (${units[0]})` : ''}
        </label>
        <input
          type="number"
          step="any"
          value={value}
          onChange={(e) => onChange(e.target.value)}
          className="mt-1 block w-full rounded-md border border-gray-300 px-3 py-2 text-sm shadow-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
        />
      </div>
    );
  }

  return (
    <div className="mb-4">
      <label className="block text-sm font-medium text-gray-700">{spec.name}</label>
      <input
        type="text"
        value={value}
        onChange={(e) => onChange(e.target.value)}
        className="mt-1 block w-full rounded-md border border-gray-300 px-3 py-2 text-sm shadow-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
      />
    </div>
  );
}

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
    datasheetUrl: '',
    specs: {},
    categoryId: null,
  });
  const [specDefs, setSpecDefs] = useState<SpecDefinition[]>([]);
  const [specValues, setSpecValues] = useState<Record<string, string>>({});
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
      datasheetUrl: part.datasheetUrl ?? '',
      specs: part.specs ?? {},
      categoryId: part.categoryId ?? null,
    });
    const existing: Record<string, string> = {};
    for (const [k, v] of Object.entries(part.specs ?? {})) {
      existing[k] = String(v);
    }
    setSpecValues(existing);
    setError(null);
  }, [open, part]);

  // Reload spec defs when category changes (while modal is open)
  useEffect(() => {
    if (!open) return;
    getSpecsForCategory(form.categoryId ?? null)
      .then((defs) => {
        setSpecDefs(defs);
        setSpecValues((prev) => {
          const next: Record<string, string> = {};
          for (const def of defs) {
            next[def.jsonName] = prev[def.jsonName] ?? '';
          }
          return next;
        });
      })
      .catch(() => setSpecDefs([]));
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [form.categoryId, open]);

  const handleSave = async () => {
    setSaving(true);
    setError(null);
    const filteredSpecs: Record<string, string> = {};
    for (const def of specDefs) {
      if (specValues[def.jsonName] !== undefined && specValues[def.jsonName] !== '') {
        filteredSpecs[def.jsonName] = specValues[def.jsonName];
      }
    }
    try {
      const updated = await updatePart(part.id, { ...form, specs: filteredSpecs });
      onSaved(updated);
    } catch (e: unknown) {
      setError((e as Error).message);
    } finally {
      setSaving(false);
    }
  };

  const catOptions = buildCatOptions(categoryTree);

  return (
    <Modal open={open} onClose={onClose} title="Edit Part">
      <div className="max-h-[70vh] overflow-y-auto pr-1">
        <FormField
          label="Part Number *"
          value={form.partNumber}
          onChange={(e) => setForm({ ...form, partNumber: e.target.value })}
          placeholder="e.g. BC547"
        />
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
        <FormField
          as="select"
          label="Category"
          value={form.categoryId ?? ''}
          onChange={(e) =>
            setForm({ ...form, categoryId: e.target.value ? Number(e.target.value) : null })
          }
        >
          <option value="">— Uncategorized —</option>
          {catOptions.map((opt) => (
            <option key={opt.id} value={opt.id}>
              {opt.label}
            </option>
          ))}
        </FormField>

        {specDefs.length > 0 ? (
          <div className="mt-2">
            <p className="mb-2 text-sm font-medium text-gray-700">Specifications</p>
            {specDefs.map((spec) => (
              <SpecField
                key={spec.id}
                spec={spec}
                value={specValues[spec.jsonName] ?? ''}
                onChange={(val) => setSpecValues((prev) => ({ ...prev, [spec.jsonName]: val }))}
              />
            ))}
          </div>
        ) : (
          form.categoryId !== null && (
            <p className="mb-4 text-xs text-gray-400">
              No spec fields defined for this category.
            </p>
          )
        )}
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
