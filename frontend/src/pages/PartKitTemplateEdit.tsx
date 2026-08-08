import { useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import {
  createPartKitTemplate,
  getCategoryTree,
  getPartKitTemplate,
  getSpecDefinitions,
  getSpecGroups,
  updatePartKitTemplate,
} from '../api';
import type {
  CategoryTree,
  PartKitTemplateRequest,
  SpecDefinition,
  SpecGroup,
} from '../api/types';
import CategoryPicker from '../components/CategoryPicker';
import FormField from '../components/FormField';
import TagInput from '../components/TagInput';

const PLACEHOLDER = '${value}';

function PlaceholderHint() {
  return (
    <p className="mb-4 -mt-3 text-xs text-gray-400">
      Use <code className="rounded bg-gray-100 px-1 font-mono dark:bg-gray-700">{PLACEHOLDER}</code>{' '}
      where the value of each part goes.
    </p>
  );
}

/**
 * Type-ahead over every spec field in the organisation, to put one on the template. Same shape as
 * the part edit modal's picker: there are several hundred definitions and a kit uses a handful.
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
      .sort((a, b) => {
        const ai = a.name.toLowerCase().indexOf(q);
        const bi = b.name.toLowerCase().indexOf(q);
        return ai !== bi ? ai - bi : a.name.localeCompare(b.name);
      })
      .slice(0, 10);
  }, [query, candidates]);

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

const emptyForm = (): PartKitTemplateRequest => ({
  name: '',
  notes: '',
  partNumberTemplate: '',
  personalNumber: false,
  manufacturerTemplate: '',
  descriptionTemplate: '',
  detailsTemplate: '',
  footprintTemplate: '',
  datasheetUrlTemplate: '',
  categoryId: null,
  specs: {},
  tags: [],
  values: [],
});

export default function PartKitTemplateEditPage() {
  const { id } = useParams();
  const navigate = useNavigate();
  const isNew = id === 'new';
  const templateId = isNew ? null : Number(id);

  const [form, setForm] = useState<PartKitTemplateRequest>(emptyForm());
  const [categoryTree, setCategoryTree] = useState<CategoryTree[]>([]);
  const [specDefs, setSpecDefs] = useState<SpecDefinition[]>([]);
  const [specGroups, setSpecGroups] = useState<SpecGroup[]>([]);
  const [specValues, setSpecValues] = useState<Record<string, string>>({});
  // Keys kept on the form even while empty — clearing a field is how you retype it, not how you
  // remove it (that is the per-row remove button). Same rule as the part edit modal.
  const [shownKeys, setShownKeys] = useState<string[]>([]);
  const [values, setValues] = useState<string[]>([]);
  const [valueInput, setValueInput] = useState('');
  const [loading, setLoading] = useState(!isNew);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const valueInputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    getCategoryTree().then(setCategoryTree).catch(() => {});
    getSpecDefinitions().then(setSpecDefs).catch(() => setSpecDefs([]));
    getSpecGroups().then(setSpecGroups).catch(() => setSpecGroups([]));
  }, []);

  useEffect(() => {
    if (templateId == null) return;
    setLoading(true);
    getPartKitTemplate(templateId)
      .then((t) => {
        setForm({
          name: t.name,
          notes: t.notes ?? '',
          partNumberTemplate: t.partNumberTemplate,
          personalNumber: t.personalNumber,
          manufacturerTemplate: t.manufacturerTemplate ?? '',
          descriptionTemplate: t.descriptionTemplate ?? '',
          detailsTemplate: t.detailsTemplate ?? '',
          footprintTemplate: t.footprintTemplate ?? '',
          datasheetUrlTemplate: t.datasheetUrlTemplate ?? '',
          categoryId: t.categoryId ?? null,
          tags: t.tags ?? [],
        });
        const specs: Record<string, string> = {};
        for (const [k, v] of Object.entries(t.specs ?? {})) specs[k] = String(v);
        setSpecValues(specs);
        setShownKeys(Object.keys(specs));
        setValues(t.values ?? []);
      })
      .catch((e: Error) => setError(e.message))
      .finally(() => setLoading(false));
  }, [templateId]);

  const isShown = (key: string) =>
    (specValues[key] !== undefined && specValues[key] !== '') || shownKeys.includes(key);

  const defsByKey = useMemo(() => new Map(specDefs.map((d) => [d.jsonName, d])), [specDefs]);

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
  }, [specDefs, specGroups, specValues, shownKeys]);

  const undefinedKeys = useMemo(
    () => Object.keys(specValues).filter((k) => !defsByKey.has(k) && isShown(k)),
  // eslint-disable-next-line react-hooks/exhaustive-deps
    [specValues, defsByKey, shownKeys]
  );

  const addableSpecs = useMemo(
    () => specDefs.filter((d) => !isShown(d.jsonName)),
  // eslint-disable-next-line react-hooks/exhaustive-deps
    [specDefs, specValues, shownKeys]
  );

  const addSpec = (spec: SpecDefinition) => {
    setShownKeys((prev) => (prev.includes(spec.jsonName) ? prev : [...prev, spec.jsonName]));
    setSpecValues((prev) => ({ ...prev, [spec.jsonName]: prev[spec.jsonName] ?? '' }));
  };

  const removeSpec = (key: string) => {
    setShownKeys((prev) => prev.filter((k) => k !== key));
    setSpecValues((prev) => {
      const next = { ...prev };
      delete next[key];
      return next;
    });
  };

  const addValue = () => {
    const v = valueInput.trim();
    if (!v) return;
    // A value entered twice would generate the same part twice — flag it rather than adding it.
    if (values.includes(v)) {
      setError(`"${v}" is already in the list`);
      return;
    }
    setValues((prev) => [...prev, v]);
    setValueInput('');
    setError(null);
  };

  const removeValue = (v: string) => setValues((prev) => prev.filter((x) => x !== v));

  const missingPlaceholder = !(form.partNumberTemplate ?? '').includes(PLACEHOLDER);

  const handleSave = async () => {
    setSaving(true);
    setError(null);
    const specs: Record<string, string> = {};
    for (const [k, v] of Object.entries(specValues)) {
      if (v !== undefined && v !== '') specs[k] = v;
    }
    const payload: PartKitTemplateRequest = { ...form, specs, values };
    try {
      const saved = templateId == null
        ? await createPartKitTemplate(payload)
        : await updatePartKitTemplate(templateId, payload);
      // Same route for both cases: creating lands on the saved template's own URL, so a second
      // Save updates rather than creating a duplicate.
      navigate(`/part-kits/${saved.id}`, { replace: true });
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setSaving(false);
    }
  };

  /** One spec row: a plain text input whatever the spec's data type is — see below. */
  const specRow = (key: string, label: string, unit?: string) => (
    <div key={key} className="flex items-start gap-2">
      <div className="min-w-0 flex-1">
        <label className="block text-sm font-medium text-gray-700">
          {label}{unit ? ` (${unit})` : ''}
        </label>
        <input
          type="text"
          value={specValues[key] ?? ''}
          onChange={(e) => setSpecValues((prev) => ({ ...prev, [key]: e.target.value }))}
          placeholder={PLACEHOLDER}
          className="mb-4 mt-1 block w-full rounded-md border border-gray-300 px-3 py-2 text-sm shadow-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
        />
      </div>
      <button
        type="button"
        onClick={() => removeSpec(key)}
        title={`Remove ${label}`}
        aria-label={`Remove ${label}`}
        className="mt-6 shrink-0 rounded p-1 text-gray-400 hover:bg-red-50 hover:text-red-600"
      >
        <svg viewBox="0 0 24 24" className="h-4 w-4" fill="none" stroke="currentColor"
             strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
          <path d="M18 6 6 18M6 6l12 12" />
        </svg>
      </button>
    </div>
  );

  if (loading) return <div className="p-8 text-sm text-gray-400">Loading…</div>;

  return (
    <div className="p-4 md:p-8">
      <div className="mb-6 flex flex-wrap items-center justify-between gap-3">
        <div>
          <button
            onClick={() => navigate('/part-kits')}
            className="mb-1 text-sm text-blue-600 hover:underline"
          >
            ← Part kits
          </button>
          <h1 className="text-2xl font-bold text-gray-900">
            {templateId == null ? 'New part kit template' : form.name || 'Part kit template'}
          </h1>
          <p className="mt-1 text-sm text-gray-500">
            One template for a pack of parts that differ in a single value.
          </p>
        </div>
        <div className="flex gap-3">
          <button
            onClick={() => navigate('/part-kits')}
            className="rounded-lg border border-gray-300 px-4 py-2 text-sm hover:bg-gray-50"
          >
            Cancel
          </button>
          <button
            onClick={handleSave}
            disabled={saving || !form.name.trim() || !form.partNumberTemplate.trim() || missingPlaceholder}
            className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-50"
          >
            {saving ? 'Saving…' : 'Save'}
          </button>
        </div>
      </div>

      {error && (
        <div className="mb-4 rounded-lg border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700">
          {error}
        </div>
      )}

      <div className="grid gap-6 lg:grid-cols-3">
        {/* ── Section 1: the part template ───────────────────────────────── */}
        <div className="lg:col-span-2 rounded-lg border border-gray-200 bg-surface p-5 shadow-sm">
          <h2 className="mb-1 text-lg font-semibold text-gray-900">Part template</h2>
          <p className="mb-4 text-sm text-gray-500">
            The same fields as a new part. Every text field may hold{' '}
            <code className="rounded bg-gray-100 px-1 font-mono dark:bg-gray-700">{PLACEHOLDER}</code>,
            which is replaced by each value in turn.
          </p>

          <FormField
            label="Kit name *"
            value={form.name}
            onChange={(e) => setForm({ ...form, name: e.target.value })}
            placeholder="e.g. E12 resistors 1/4W 5%"
          />
          <FormField
            as="textarea"
            label="Notes"
            value={form.notes ?? ''}
            onChange={(e) => setForm({ ...form, notes: e.target.value })}
            rows={2}
            placeholder="About the kit itself — supplier, order code, …"
          />

          <hr className="mb-5 mt-1 border-gray-200" />

          <FormField
            label="Part number *"
            value={form.partNumberTemplate}
            onChange={(e) => setForm({ ...form, partNumberTemplate: e.target.value })}
            placeholder={`e.g. RES-${PLACEHOLDER}-0805`}
          />
          {missingPlaceholder ? (
            <p className="mb-4 -mt-3 text-xs text-amber-600">
              The part number must contain{' '}
              <code className="rounded bg-amber-100 px-1 font-mono text-amber-800">{PLACEHOLDER}</code>{' '}
              — otherwise every value would generate the same part.
            </p>
          ) : (
            <PlaceholderHint />
          )}
          <label className="mb-3 flex items-center gap-2 text-sm text-gray-700 dark:text-gray-300">
            <input
              type="checkbox"
              checked={form.personalNumber ?? false}
              onChange={(e) => setForm({ ...form, personalNumber: e.target.checked })}
            />
            These are personal/internal numbers, not real manufacturer part numbers
          </label>
          <FormField
            label="Manufacturer"
            value={form.manufacturerTemplate ?? ''}
            onChange={(e) => setForm({ ...form, manufacturerTemplate: e.target.value })}
          />
          <FormField
            as="textarea"
            label="Description"
            value={form.descriptionTemplate ?? ''}
            onChange={(e) => setForm({ ...form, descriptionTemplate: e.target.value })}
            rows={2}
            placeholder={`e.g. ${PLACEHOLDER} resistor, 0805, 1/4W, 5%`}
          />
          <FormField
            as="textarea"
            label="Details"
            value={form.detailsTemplate ?? ''}
            onChange={(e) => setForm({ ...form, detailsTemplate: e.target.value })}
            rows={4}
          />
          <FormField
            label="Footprint"
            value={form.footprintTemplate ?? ''}
            onChange={(e) => setForm({ ...form, footprintTemplate: e.target.value })}
            placeholder="e.g. 0805"
          />
          <FormField
            label="Datasheet URL"
            value={form.datasheetUrlTemplate ?? ''}
            onChange={(e) => setForm({ ...form, datasheetUrlTemplate: e.target.value })}
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
            <p className="mb-1 text-sm font-medium text-gray-700">Specifications</p>
            <p className="mb-3 text-xs text-gray-400">
              Every spec is edited as text here, whatever its type — a template holds{' '}
              <code className="rounded bg-gray-100 px-1 font-mono dark:bg-gray-700">{PLACEHOLDER}</code>,
              which no number or dropdown could accept. The generated parts carry the substituted value.
            </p>

            {shownGroups.length === 0 && undefinedKeys.length === 0 && (
              <p className="mb-3 text-xs text-gray-400">
                No specifications on this template yet. Search below to add one.
              </p>
            )}

            {shownGroups.map((group) => (
              <div key={group.name} className="mb-4">
                <h4 className="mb-2 border-b border-gray-200 pb-1 text-xs font-semibold uppercase tracking-wider text-blue-700/80">
                  {group.name}
                </h4>
                {group.specs.map((spec) => specRow(spec.jsonName, spec.name, spec.unit))}
              </div>
            ))}

            {undefinedKeys.length > 0 && (
              <div className="mb-4">
                <h4 className="mb-2 border-b border-gray-200 pb-1 text-xs font-semibold uppercase tracking-wider text-gray-500">
                  Other
                </h4>
                {undefinedKeys.map((key) => specRow(key, key))}
              </div>
            )}

            <AddSpecSearch candidates={addableSpecs} onPick={addSpec} />
          </div>
        </div>

        {/* ── Section 2: the values ──────────────────────────────────────── */}
        <div className="rounded-lg border border-gray-200 bg-surface p-5 shadow-sm lg:sticky lg:top-4 lg:self-start">
          <h2 className="mb-1 text-lg font-semibold text-gray-900">
            Values <span className="text-sm font-normal text-gray-400">({values.length})</span>
          </h2>
          <p className="mb-4 text-sm text-gray-500">
            One part per value. Type a value and press Enter.
          </p>

          <div className="flex gap-2">
            <input
              ref={valueInputRef}
              type="text"
              value={valueInput}
              onChange={(e) => setValueInput(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter') {
                  e.preventDefault();
                  addValue();
                }
              }}
              placeholder="e.g. 10k"
              className="block flex-1 rounded-md border border-gray-300 px-3 py-2 text-sm shadow-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
            />
            <button
              type="button"
              onClick={addValue}
              disabled={!valueInput.trim()}
              className="rounded-lg border border-gray-300 px-3 py-2 text-sm hover:bg-gray-50 disabled:opacity-50"
            >
              Add
            </button>
          </div>

          {values.length === 0 ? (
            <p className="mt-4 text-xs text-gray-400">
              No values yet. Without at least one, there is nothing to generate.
            </p>
          ) : (
            <ul className="mt-4 max-h-[28rem] divide-y divide-gray-100 overflow-y-auto rounded-md border border-gray-200">
              {values.map((v, i) => (
                <li key={v} className="flex items-center justify-between gap-2 px-3 py-2 text-sm">
                  <span className="flex min-w-0 items-baseline gap-2">
                    <span className="w-6 shrink-0 text-xs text-gray-400">{i + 1}.</span>
                    <span className="truncate font-mono text-gray-900">{v}</span>
                  </span>
                  <button
                    type="button"
                    onClick={() => removeValue(v)}
                    title={`Remove ${v}`}
                    aria-label={`Remove ${v}`}
                    className="shrink-0 rounded p-1 text-gray-400 hover:bg-red-50 hover:text-red-600"
                  >
                    <svg viewBox="0 0 24 24" className="h-4 w-4" fill="none" stroke="currentColor"
                         strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                      <path d="M18 6 6 18M6 6l12 12" />
                    </svg>
                  </button>
                </li>
              ))}
            </ul>
          )}

          {/* What the first value actually produces — the cheapest way to catch a typo'd
              placeholder before thirty parts carry it. */}
          {values.length > 0 && form.partNumberTemplate && (
            <div className="mt-4 rounded-md border border-gray-200 bg-gray-50 p-3 text-xs text-gray-600">
              <p className="mb-1 font-medium text-gray-500">First part would be</p>
              <p className="font-mono text-gray-900">
                {form.partNumberTemplate.split(PLACEHOLDER).join(values[0])}
              </p>
              {form.descriptionTemplate && (
                <p className="mt-1 text-gray-600">
                  {form.descriptionTemplate.split(PLACEHOLDER).join(values[0])}
                </p>
              )}
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
