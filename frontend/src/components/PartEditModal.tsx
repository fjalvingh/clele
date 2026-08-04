import { useEffect, useMemo, useRef, useState } from 'react';
import { getCategoryTree, getSpecDefinitions, getSpecGroups, updatePart } from '../api';
import type { CategoryTree, Part, PartRequest, SpecDefinition, SpecGroup } from '../api/types';
import CategoryPicker from './CategoryPicker';
import FormField from './FormField';
import MetricNumberField from './MetricNumberField';
import Modal from './Modal';
import TagInput from './TagInput';

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

/** A spec row: the typed field plus the control that takes the spec off the part again. */
function SpecRow({
  spec,
  value,
  onChange,
  onRemove,
}: {
  spec: SpecDefinition;
  value: string;
  onChange: (val: string) => void;
  onRemove: () => void;
}) {
  return (
    <div className="flex items-start gap-2">
      <div className="min-w-0 flex-1">
        <SpecField spec={spec} value={value} onChange={onChange} />
      </div>
      <button
        type="button"
        onClick={onRemove}
        title={`Remove ${spec.name}`}
        aria-label={`Remove ${spec.name}`}
        className="mt-6 shrink-0 rounded p-1 text-gray-400 hover:bg-red-50 hover:text-red-600"
      >
        <svg viewBox="0 0 24 24" className="h-4 w-4" fill="none" stroke="currentColor"
             strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
          <path d="M18 6 6 18M6 6l12 12" />
        </svg>
      </button>
    </div>
  );
}

/**
 * Type-ahead over every spec field in the organisation, to put one on the part. Searching beats a
 * long select: there are several hundred definitions, and the point of this screen is to show only
 * the handful a part actually has.
 */
function AddSpecSearch({
  candidates,
  onPick,
}: {
  candidates: SpecDefinition[];
  onPick: (spec: SpecDefinition) => void;
}) {
  const [query, setQuery] = useState('');
  const [open, setOpen] = useState(false);
  const boxRef = useRef<HTMLDivElement>(null);

  const matches = useMemo(() => {
    const q = query.trim().toLowerCase();
    if (!q) return [];
    return candidates
      .filter((s) => s.name.toLowerCase().includes(q) || s.jsonName.toLowerCase().includes(q))
      // Prefix matches first — typing "volt" should reach "Voltage" before "Supply Voltage".
      .sort((a, b) => {
        const ai = a.name.toLowerCase().indexOf(q);
        const bi = b.name.toLowerCase().indexOf(q);
        return ai !== bi ? ai - bi : a.name.localeCompare(b.name);
      })
      .slice(0, 10);
  }, [query, candidates]);

  // Close on an outside click, so the list doesn't hang over the rest of the form.
  useEffect(() => {
    const onDown = (e: MouseEvent) => {
      if (boxRef.current && !boxRef.current.contains(e.target as Node)) setOpen(false);
    };
    document.addEventListener('mousedown', onDown);
    return () => document.removeEventListener('mousedown', onDown);
  }, []);

  const pick = (spec: SpecDefinition) => {
    onPick(spec);
    setQuery('');
    setOpen(false);
  };

  return (
    <div ref={boxRef} className="relative">
      <label className="block text-sm font-medium text-gray-700">Add a specification</label>
      <input
        type="text"
        value={query}
        onChange={(e) => {
          setQuery(e.target.value);
          setOpen(true);
        }}
        onFocus={() => setOpen(true)}
        onKeyDown={(e) => {
          if (e.key === 'Enter' && matches.length > 0) {
            e.preventDefault();
            pick(matches[0]);
          } else if (e.key === 'Escape') {
            setOpen(false);
          }
        }}
        placeholder="Search spec fields by name…"
        className="mt-1 block w-full rounded-md border border-gray-300 px-3 py-2 text-sm shadow-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
      />
      {open && query.trim() !== '' && (
        <div className="absolute z-10 mt-1 max-h-64 w-full overflow-y-auto rounded-md border border-gray-200 bg-surface shadow-lg">
          {matches.length === 0 ? (
            <p className="px-3 py-2 text-sm text-gray-400">
              No spec field matches — add it on the Spec Fields screen first.
            </p>
          ) : (
            matches.map((spec) => (
              <button
                key={spec.id}
                type="button"
                onClick={() => pick(spec)}
                className="block w-full px-3 py-2 text-left text-sm hover:bg-blue-50"
              >
                <span className="font-medium text-gray-900">{spec.name}</span>
                {spec.unit ? <span className="text-gray-500"> ({spec.unit})</span> : null}
                <span className="block text-xs text-gray-500">{spec.groupName}</span>
              </button>
            ))
          )}
        </div>
      )}
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
    personalNumber: false,
    datasheetUrl: '',
    specs: {},
    categoryId: null,
    tags: [],
  });
  const [specDefs, setSpecDefs] = useState<SpecDefinition[]>([]);
  const [specGroups, setSpecGroups] = useState<SpecGroup[]>([]);
  const [specValues, setSpecValues] = useState<Record<string, string>>({});
  // Specs added during this edit: shown while still empty, so a just-picked field can be typed into.
  const [addedKeys, setAddedKeys] = useState<string[]>([]);
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
    setAddedKeys([]);
    setError(null);
  }, [open, part]);

  // Every definition in the organisation, plus the groups for their display order. Not the
  // category-scoped subset: what is shown is driven by the values the part has, and a part can
  // carry a spec its category does not list.
  useEffect(() => {
    if (!open) return;
    getSpecDefinitions().then(setSpecDefs).catch(() => setSpecDefs([]));
    getSpecGroups().then(setSpecGroups).catch(() => setSpecGroups([]));
  }, [open]);

  // A spec is on the form when it holds a value, or was just picked from the search.
  const isShown = (key: string) =>
    (specValues[key] !== undefined && specValues[key] !== '') || addedKeys.includes(key);

  const defsByKey = useMemo(
    () => new Map(specDefs.map((d) => [d.jsonName, d])),
    [specDefs]
  );

  // Shown specs, bucketed into their groups, groups in their configured display order.
  const shownGroups = useMemo(() => {
    const order = new Map(specGroups.map((g, i) => [g.name, i]));
    const buckets = new Map<string, SpecDefinition[]>();
    for (const def of specDefs) {
      if (!isShown(def.jsonName)) continue;
      const name = def.groupName ?? 'Other';
      const bucket = buckets.get(name);
      if (bucket) bucket.push(def);
      else buckets.set(name, [def]);
    }
    return [...buckets.entries()]
      .map(([name, specs]) => ({ name, specs }))
      .sort((a, b) => (order.get(a.name) ?? 999) - (order.get(b.name) ?? 999));
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [specDefs, specGroups, specValues, addedKeys]);

  // Values the part carries under a key no definition covers.
  const undefinedKeys = useMemo(
    () => Object.keys(specValues).filter((k) => !defsByKey.has(k) && isShown(k)),
  // eslint-disable-next-line react-hooks/exhaustive-deps
    [specValues, defsByKey, addedKeys]
  );

  // The search offers what is not already on the form.
  const addableSpecs = useMemo(
    () => specDefs.filter((d) => !isShown(d.jsonName)),
  // eslint-disable-next-line react-hooks/exhaustive-deps
    [specDefs, specValues, addedKeys]
  );

  const addSpec = (spec: SpecDefinition) => {
    setAddedKeys((prev) => (prev.includes(spec.jsonName) ? prev : [...prev, spec.jsonName]));
    setSpecValues((prev) => ({ ...prev, [spec.jsonName]: prev[spec.jsonName] ?? '' }));
  };

  const removeSpec = (key: string) => {
    setAddedKeys((prev) => prev.filter((k) => k !== key));
    setSpecValues((prev) => {
      const next = { ...prev };
      delete next[key];
      return next;
    });
  };

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
      const updated = await updatePart(part.id, { ...form, specs: filteredSpecs });
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

        <div className="mt-2">
          <p className="mb-2 text-sm font-medium text-gray-700">Specifications</p>

          {shownGroups.length === 0 && undefinedKeys.length === 0 && (
            <p className="mb-3 text-xs text-gray-400">
              This part has no specifications yet. Search below to add one.
            </p>
          )}

          {shownGroups.map((group) => (
            <div key={group.name} className="mb-4">
              <h4 className="mb-2 border-b border-gray-200 pb-1 text-xs font-semibold uppercase tracking-wider text-blue-700/80">
                {group.name}
              </h4>
              {group.specs.map((spec) => (
                <SpecRow
                  key={spec.id}
                  spec={spec}
                  value={specValues[spec.jsonName] ?? ''}
                  onChange={(val) => setSpecValues((prev) => ({ ...prev, [spec.jsonName]: val }))}
                  onRemove={() => removeSpec(spec.jsonName)}
                />
              ))}
            </div>
          ))}

          {/* Values whose key no definition covers — editable as plain text so they are not lost. */}
          {undefinedKeys.length > 0 && (
            <div className="mb-4">
              <h4 className="mb-2 border-b border-gray-200 pb-1 text-xs font-semibold uppercase tracking-wider text-gray-500">
                Other
              </h4>
              {undefinedKeys.map((key) => (
                <SpecRow
                  key={key}
                  spec={{
                    id: -1,
                    jsonName: key,
                    name: key,
                    dataType: 'TEXT',
                    displayOrder: 0,
                    groupId: -1,
                    groupName: 'Other',
                  }}
                  value={specValues[key] ?? ''}
                  onChange={(val) => setSpecValues((prev) => ({ ...prev, [key]: val }))}
                  onRemove={() => removeSpec(key)}
                />
              ))}
            </div>
          )}

          <AddSpecSearch candidates={addableSpecs} onPick={addSpec} />
        </div>
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
