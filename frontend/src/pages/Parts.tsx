import { useEffect, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import {
  createPart,
  getCategoryTree,
  getParts,
  getSpecsForCategory,
} from '../api';
import type { CategoryTree, Part, PartRequest, SpecDefinition } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import Badge from '../components/Badge';
import CategoryPicker from '../components/CategoryPicker';
import DataTable from '../components/DataTable';
import type { Column } from '../components/DataTable';
import FormField from '../components/FormField';
import MetricNumberField from '../components/MetricNumberField';
import Modal from '../components/Modal';
import TagInput from '../components/TagInput';


const emptyForm = (): PartRequest => ({
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

// Split "64 KB" → ["64", "KB"] given units list; falls back to [value, first unit]
function parseMultiUnit(value: string, units: string[]): [string, string] {
  for (const u of units) {
    if (value.endsWith(' ' + u)) return [value.slice(0, -(u.length + 1)), u];
  }
  return [value, units[0] ?? ''];
}

// Render a single spec input based on its type
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

  // TEXT (default)
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

type SortKey = 'partNumber' | 'manufacturer';

export default function PartsPage() {
  const { hasPermission } = useAuth();
  const canEdit = hasPermission('PARTS_EDIT');
  const navigate = useNavigate();
  // Search criteria are mirrored in the URL query string so navigating into a part and back
  // (or reloading) restores the same results instead of showing an empty list.
  const [searchParams, setSearchParams] = useSearchParams();
  const [parts, setParts] = useState<Part[]>([]);
  const [categoryTree, setCategoryTree] = useState<CategoryTree[]>([]);
  const [search, setSearch] = useState(searchParams.get('q') ?? '');
  const [filterCategoryId, setFilterCategoryId] = useState<number | undefined>(
    searchParams.get('cat') ? Number(searchParams.get('cat')) : undefined,
  );
  const [sort, setSort] = useState<SortKey>(
    searchParams.get('sort') === 'manufacturer' ? 'manufacturer' : 'partNumber',
  );
  const [loading, setLoading] = useState(true);
  const [searched, setSearched] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [modalOpen, setModalOpen] = useState(false);
  const [form, setForm] = useState<PartRequest>(emptyForm());
  const [specValues, setSpecValues] = useState<Record<string, string>>({});
  const [specDefs, setSpecDefs] = useState<SpecDefinition[]>([]);
  const [saving, setSaving] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);

  const loadParts = (s?: string, cid?: number, sortBy: string = sort) => {
    setSearched(true);
    getParts(s, cid, sortBy)
      .then(setParts)
      .catch((e: Error) => setError(e.message));
  };

  // Persist the criteria to the URL (so Back / reload restores them) and run the search.
  const runSearch = (s: string, cid: number | undefined, sortBy: SortKey) => {
    const params: Record<string, string> = {};
    if (s.trim()) params.q = s.trim();
    if (cid !== undefined) params.cat = String(cid);
    if (sortBy !== 'partNumber') params.sort = sortBy;
    setSearchParams(params, { replace: true });
    loadParts(s.trim() || undefined, cid, sortBy);
  };

  // Load the category tree up front — parts are fetched on demand once the user searches,
  // so opening the page is fast even with a large catalogue.
  useEffect(() => {
    setLoading(true);
    getCategoryTree()
      .then(setCategoryTree)
      .catch((e: Error) => setError(e.message))
      .finally(() => setLoading(false));
  }, []);

  // Restore results when the page mounts with search criteria in the URL (back navigation / reload).
  useEffect(() => {
    const q = searchParams.get('q') ?? '';
    const cid = searchParams.get('cat') ? Number(searchParams.get('cat')) : undefined;
    if (q.trim() || cid !== undefined) {
      loadParts(q.trim() || undefined, cid, sort);
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // When modal category changes, reload spec definitions
  useEffect(() => {
    if (!modalOpen) return;
    getSpecsForCategory(form.categoryId ?? null)
      .then((defs) => {
        setSpecDefs(defs);
        // Preserve existing values for matching keys; clear unmatched keys
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
  }, [form.categoryId, modalOpen]);

  const handleSearch = (e: React.FormEvent) => {
    e.preventDefault();
    if (!search.trim() && filterCategoryId === undefined) {
      // Nothing to search on — keep the page empty rather than loading the whole catalogue.
      setSearchParams({}, { replace: true });
      setParts([]);
      setSearched(false);
      return;
    }
    runSearch(search, filterCategoryId, sort);
  };

  const openCreate = () => {
    const f = emptyForm();
    setForm(f);
    setSpecValues({});
    setSpecDefs([]);
    setFormError(null);
    setModalOpen(true);
  };

  const handleSave = async () => {
    setSaving(true);
    setFormError(null);
    // Only include spec values for keys present in current spec definitions
    const filteredSpecs: Record<string, string> = {};
    for (const def of specDefs) {
      if (specValues[def.jsonName] !== undefined && specValues[def.jsonName] !== '') {
        filteredSpecs[def.jsonName] = specValues[def.jsonName];
      }
    }
    const payload: PartRequest = { ...form, specs: filteredSpecs };
    try {
      await createPart(payload);
      setModalOpen(false);
      loadParts(search || undefined, filterCategoryId);
    } catch (e: unknown) {
      setFormError((e as Error).message);
    } finally {
      setSaving(false);
    }
  };

  const columns: Column<Part>[] = [
    {
      key: 'partNumber',
      header: 'Part #',
      render: (row) => (
        <span className="inline-flex items-center gap-1.5">
          <span className="font-mono text-blue-600">{row.partNumber}</span>
          {row.personalNumber && (
            <span
              className="rounded-md bg-amber-100 px-1.5 py-0.5 text-[10px] font-medium text-amber-700 ring-1 ring-inset ring-amber-600/20"
              title="Personal/internal number, not a real manufacturer part number"
            >
              P
            </span>
          )}
        </span>
      ),
    },
    { key: 'description', header: 'Description', render: (r) => r.description ?? '—' },
    { key: 'stock', header: 'In Stock', render: (r) => r.totalQuantity ?? 0 },
    {
      key: 'category',
      header: 'Category',
      render: (r) => r.categoryBreadcrumb ?? '—',
    },
    {
      key: 'tags',
      header: 'Tags',
      render: (r) =>
        r.tags && r.tags.length > 0 ? (
          <div className="flex flex-wrap gap-1">
            {r.tags.map((t) => (
              <Badge key={t} variant="blue">{t}</Badge>
            ))}
          </div>
        ) : (
          '—'
        ),
    },
  ];

  return (
    <div className="p-8">
      <div className="mb-6 flex items-center justify-between">
        <h1 className="text-2xl font-bold text-gray-900">Parts</h1>
        {canEdit && (
          <div className="flex gap-3">
            <button
              onClick={openCreate}
              className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700"
            >
              + New Part
            </button>
          </div>
        )}
      </div>

      {/* Search / filter bar */}
      <form onSubmit={handleSearch} className="mb-6 flex gap-3">
        <input
          type="text"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          placeholder="Search by part number or description…"
          className="flex-1 rounded-lg border border-gray-300 px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
        />
        <CategoryPicker
          categories={categoryTree}
          value={filterCategoryId ?? null}
          onChange={(id) => setFilterCategoryId(id ?? undefined)}
          emptyLabel="All categories"
          className="w-48 shrink-0"
        />
        <select
          value={sort}
          onChange={(e) => {
            const next = e.target.value as SortKey;
            setSort(next);
            // Re-run the current search with the new ordering if results are showing.
            if (searched) runSearch(search, filterCategoryId, next);
          }}
          title="Sort results by"
          className="rounded-lg border border-gray-300 px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
        >
          <option value="partNumber">Sort: Part #</option>
          <option value="manufacturer">Sort: Manufacturer</option>
        </select>
        <button
          type="submit"
          className="rounded-lg bg-neutral-700 px-4 py-2 text-sm font-medium text-white hover:bg-neutral-800"
        >
          Search
        </button>
        <button
          type="button"
          onClick={() => {
            setSearch('');
            setFilterCategoryId(undefined);
            setParts([]);
            setSearched(false);
            setSearchParams({}, { replace: true });
          }}
          className="rounded-lg border border-gray-300 px-4 py-2 text-sm hover:bg-gray-50"
        >
          Clear
        </button>
      </form>

      {loading && <p className="text-gray-500">Loading...</p>}
      {error && <p className="text-red-600">{error}</p>}

      {!loading && !searched && (
        <div className="rounded-lg border border-dashed border-gray-300 bg-gray-50 p-10 text-center text-gray-500">
          Enter a search term or pick a category, then press <span className="font-medium">Search</span> to find parts.
        </div>
      )}

      {!loading && searched && (
        <DataTable
          columns={columns}
          data={parts}
          keyExtractor={(p) => p.id}
          onRowClick={(part) =>
            navigate(`/parts/${part.id}`, {
              state: { from: searchParams.toString() ? `/parts?${searchParams.toString()}` : '/parts' },
            })
          }
        />
      )}

      {/* Create/Edit Modal */}
      <Modal
        open={modalOpen}
        onClose={() => setModalOpen(false)}
        title="New Part"
      >
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

          {/* Dynamic spec fields */}
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
        {formError && <p className="mb-3 text-sm text-red-600">{formError}</p>}
        <div className="flex justify-end gap-3 pt-2">
          <button
            onClick={() => setModalOpen(false)}
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
    </div>
  );
}
