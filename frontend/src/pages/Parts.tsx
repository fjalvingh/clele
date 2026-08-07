import { useEffect, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import {
  attachmentUrl,
  createPart,
  getCategoryTree,
  getLocations,
  getParts,
  getSpecsForCategory,
} from '../api';
import type {
  CategoryTree,
  Location,
  Part,
  PartFilters,
  PartRequest,
  SpecDefinition,
} from '../api/types';
import { SPARSE_SPEC_THRESHOLD } from '../api/types';
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

/** Tri-state for the "Personal product code" filter: unset means "don't filter on it". */
type TriState = '' | 'yes' | 'no';

/** Everything the Parts search runs on. Mirrored in the URL so Back / reload restores the search. */
interface Criteria {
  search: string;
  categoryId?: number;
  sort: SortKey;
  personalNumber: TriState;
  manufacturer: string;
  locationId?: number;
  sparseSpecs: boolean;
  tags: string[];
}

const criteriaFromParams = (p: URLSearchParams): Criteria => ({
  search: p.get('q') ?? '',
  categoryId: p.get('cat') ? Number(p.get('cat')) : undefined,
  sort: p.get('sort') === 'manufacturer' ? 'manufacturer' : 'partNumber',
  personalNumber: p.get('pn') === 'yes' || p.get('pn') === 'no' ? (p.get('pn') as TriState) : '',
  manufacturer: p.get('mfr') ?? '',
  locationId: p.get('loc') ? Number(p.get('loc')) : undefined,
  sparseSpecs: p.get('sparse') === '1',
  tags: p.get('tags') ? p.get('tags')!.split(',').filter(Boolean) : [],
});

const paramsFromCriteria = (c: Criteria): Record<string, string> => {
  const params: Record<string, string> = {};
  if (c.search.trim()) params.q = c.search.trim();
  if (c.categoryId !== undefined) params.cat = String(c.categoryId);
  if (c.sort !== 'partNumber') params.sort = c.sort;
  if (c.personalNumber) params.pn = c.personalNumber;
  if (c.manufacturer.trim()) params.mfr = c.manufacturer.trim();
  if (c.locationId !== undefined) params.loc = String(c.locationId);
  if (c.sparseSpecs) params.sparse = '1';
  if (c.tags.length > 0) params.tags = c.tags.join(',');
  return params;
};

const filtersFromCriteria = (c: Criteria): PartFilters => ({
  personalNumber: c.personalNumber ? c.personalNumber === 'yes' : undefined,
  manufacturer: c.manufacturer.trim() || undefined,
  locationId: c.locationId,
  sparseSpecs: c.sparseSpecs || undefined,
  tags: c.tags,
});

/**
 * True when the criteria narrow anything down — an empty search must not list the whole catalogue.
 * The sparse-specs flag counts on its own: the dashboard tile links straight to `/parts?sparse=1`
 * with nothing else set, and leaving it out here would land the user on an empty page.
 */
const hasCriteria = (c: Criteria) =>
  Boolean(
    c.search.trim() ||
      c.categoryId !== undefined ||
      c.personalNumber ||
      c.manufacturer.trim() ||
      c.locationId !== undefined ||
      c.sparseSpecs ||
      c.tags.length > 0,
  );

/** True when any of the *advanced* (panel) filters are in use — used to auto-open the panel. */
const hasAdvanced = (c: Criteria) =>
  Boolean(
    c.personalNumber ||
      c.manufacturer.trim() ||
      c.locationId !== undefined ||
      c.sparseSpecs ||
      c.tags.length > 0,
  );

export default function PartsPage() {
  const { hasPermission } = useAuth();
  const canEdit = hasPermission('PARTS_EDIT');
  const navigate = useNavigate();
  // Search criteria are mirrored in the URL query string so navigating into a part and back
  // (or reloading) restores the same results instead of showing an empty list.
  const [searchParams, setSearchParams] = useSearchParams();
  const [parts, setParts] = useState<Part[]>([]);
  const [categoryTree, setCategoryTree] = useState<CategoryTree[]>([]);
  const [locations, setLocations] = useState<Location[]>([]);
  const [criteria, setCriteria] = useState<Criteria>(() => criteriaFromParams(searchParams));
  // The advanced panel is closed by default, but opens itself when the restored URL uses it —
  // otherwise the results would be filtered by controls the user cannot see.
  const [advancedOpen, setAdvancedOpen] = useState(() => hasAdvanced(criteriaFromParams(searchParams)));
  const [loading, setLoading] = useState(true);
  const [searched, setSearched] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [modalOpen, setModalOpen] = useState(false);
  const [form, setForm] = useState<PartRequest>(emptyForm());
  const [specValues, setSpecValues] = useState<Record<string, string>>({});
  const [specDefs, setSpecDefs] = useState<SpecDefinition[]>([]);
  const [saving, setSaving] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);

  const loadParts = (c: Criteria) => {
    setSearched(true);
    getParts(c.search.trim() || undefined, c.categoryId, c.sort, filtersFromCriteria(c))
      .then(setParts)
      .catch((e: Error) => setError(e.message));
  };

  // Persist the criteria to the URL (so Back / reload restores them) and run the search.
  const runSearch = (c: Criteria) => {
    setSearchParams(paramsFromCriteria(c), { replace: true });
    loadParts(c);
  };

  // Load the category tree and locations up front — parts are fetched on demand once the user
  // searches, so opening the page is fast even with a large catalogue.
  useEffect(() => {
    setLoading(true);
    Promise.all([getCategoryTree(), getLocations()])
      .then(([tree, locs]) => {
        setCategoryTree(tree);
        setLocations(locs);
      })
      .catch((e: Error) => setError(e.message))
      .finally(() => setLoading(false));
  }, []);

  // Restore results when the page mounts with search criteria in the URL (back navigation / reload).
  useEffect(() => {
    const restored = criteriaFromParams(searchParams);
    if (hasCriteria(restored)) loadParts(restored);
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
    if (!hasCriteria(criteria)) {
      // Nothing to search on — keep the page empty rather than loading the whole catalogue.
      setSearchParams({}, { replace: true });
      setParts([]);
      setSearched(false);
      return;
    }
    runSearch(criteria);
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
      if (hasCriteria(criteria)) loadParts(criteria);
    } catch (e: unknown) {
      setFormError((e as Error).message);
    } finally {
      setSaving(false);
    }
  };

  const columns: Column<Part>[] = [
    {
      key: 'thumbnail',
      header: '',
      render: (row) =>
        row.thumbnailId ? (
          <img
            src={attachmentUrl(row.id, row.thumbnailId)}
            alt=""
            loading="lazy"
            className="h-10 w-10 rounded-md object-contain ring-1 ring-gray-200 bg-white"
          />
        ) : (
          // Keep the column width stable so rows with and without a photo line up.
          <div className="h-10 w-10 rounded-md ring-1 ring-gray-100" />
        ),
    },
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
    <div className="p-4 md:p-8">
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

      {/* Search / filter bar, with a collapsible panel of extra fields underneath */}
      <form onSubmit={handleSearch} className="mb-6">
        {/* Wraps below sm: the five controls need ~795px side by side, so on a phone they used to
            run off the page and Search could only be reached by panning the whole view sideways.
            At sm+ the sm: overrides restore the original single row. */}
        <div className="flex flex-wrap gap-3">
          <input
            type="text"
            value={criteria.search}
            onChange={(e) => setCriteria({ ...criteria, search: e.target.value })}
            placeholder="Search by part number, description or spec…"
            className="basis-full rounded-lg border border-gray-300 px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500 sm:flex-1"
          />
          <CategoryPicker
            categories={categoryTree}
            value={criteria.categoryId ?? null}
            onChange={(id) => setCriteria({ ...criteria, categoryId: id ?? undefined })}
            emptyLabel="All categories"
            className="w-full shrink-0 sm:w-48"
          />
          <select
            value={criteria.sort}
            onChange={(e) => {
              const next = { ...criteria, sort: e.target.value as SortKey };
              setCriteria(next);
              // Re-run the current search with the new ordering if results are showing.
              if (searched) runSearch(next);
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
              setCriteria({
                search: '',
                sort: criteria.sort,
                personalNumber: '',
                manufacturer: '',
                sparseSpecs: false,
                tags: [],
              });
              setParts([]);
              setSearched(false);
              setSearchParams({}, { replace: true });
            }}
            className="rounded-lg border border-gray-300 px-4 py-2 text-sm hover:bg-gray-50"
          >
            Clear
          </button>
        </div>

        <button
          type="button"
          onClick={() => setAdvancedOpen((v) => !v)}
          className="mt-2 inline-flex items-center gap-1.5 text-sm text-gray-600 hover:text-gray-900 dark:text-gray-400 dark:hover:text-gray-200"
        >
          <svg
            className={`h-4 w-4 transition-transform ${advancedOpen ? 'rotate-90' : ''}`}
            viewBox="0 0 20 20"
            fill="none"
            stroke="currentColor"
            strokeWidth="2"
          >
            <path d="M7 5l6 5-6 5" strokeLinecap="round" strokeLinejoin="round" />
          </svg>
          More search options
          {!advancedOpen && hasAdvanced(criteria) && (
            <span className="rounded-full bg-blue-100 px-2 py-0.5 text-[11px] font-medium text-blue-700">
              active
            </span>
          )}
        </button>

        {advancedOpen && (
          <div className="mt-2 grid grid-cols-1 gap-4 rounded-lg border border-gray-200 bg-gray-50 p-4 sm:grid-cols-2 lg:grid-cols-4 dark:border-gray-700 dark:bg-gray-800/50">
            <div>
              <label className="block text-sm font-medium text-gray-700 dark:text-gray-300">
                Personal product code
              </label>
              <select
                value={criteria.personalNumber}
                onChange={(e) =>
                  setCriteria({ ...criteria, personalNumber: e.target.value as TriState })
                }
                className="mt-1 block w-full rounded-lg border border-gray-300 px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
              >
                <option value="">Any</option>
                <option value="yes">Yes</option>
                <option value="no">No</option>
              </select>
            </div>

            <div>
              <label className="block text-sm font-medium text-gray-700 dark:text-gray-300">
                Location
              </label>
              <select
                value={criteria.locationId ?? ''}
                onChange={(e) =>
                  setCriteria({
                    ...criteria,
                    locationId: e.target.value ? Number(e.target.value) : undefined,
                  })
                }
                title="Parts with stock in this location or anywhere below it"
                className="mt-1 block w-full rounded-lg border border-gray-300 px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
              >
                <option value="">Any location</option>
                {locations.map((l) => (
                  <option key={l.id} value={l.id}>{l.breadcrumb}</option>
                ))}
              </select>
            </div>

            <div>
              <label className="block text-sm font-medium text-gray-700 dark:text-gray-300">
                Manufacturer
              </label>
              <input
                type="text"
                value={criteria.manufacturer}
                onChange={(e) => setCriteria({ ...criteria, manufacturer: e.target.value })}
                placeholder="e.g. Texas Instruments"
                className="mt-1 block w-full rounded-lg border border-gray-300 px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
              />
            </div>

            <div>
              {/* TagInput renders its own "Tags" label. A part must carry every tag listed here. */}
              <TagInput
                value={criteria.tags}
                onChange={(tags) => setCriteria({ ...criteria, tags })}
                allowCreate={false}
              />
            </div>

            <div className="flex items-end">
              {/* The other end of the dashboard's "parts missing specs" tile, which links here
                  with ?sparse=1. */}
              <label
                className="inline-flex items-center gap-2 text-sm font-medium text-gray-700 dark:text-gray-300"
                title={`Parts carrying fewer than ${SPARSE_SPEC_THRESHOLD} specification values`}
              >
                <input
                  type="checkbox"
                  checked={criteria.sparseSpecs}
                  onChange={(e) => setCriteria({ ...criteria, sparseSpecs: e.target.checked })}
                  className="h-4 w-4 rounded border-gray-300 text-blue-600 focus:ring-blue-500"
                />
                Missing specs (under {SPARSE_SPEC_THRESHOLD})
              </label>
            </div>
          </div>
        )}
      </form>

      {loading && <p className="text-gray-500">Loading...</p>}
      {error && <p className="text-red-600">{error}</p>}

      {!loading && !searched && (
        <div className="rounded-lg border border-dashed border-gray-300 bg-gray-50 p-10 text-center text-gray-500">
          Enter a search term, pick a category or set one of the extra search options, then press{' '}
          <span className="font-medium">Search</span> to find parts.
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
