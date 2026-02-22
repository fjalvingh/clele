import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { createPart, deletePart, getCategories, getCategoryTree, getParts, getSpecsForCategory, updatePart } from '../api';
import type { Category, CategoryTree, Part, PartRequest, SpecDefinition } from '../api/types';
import DataTable from '../components/DataTable';
import type { Column } from '../components/DataTable';
import FormField from '../components/FormField';
import Modal from '../components/Modal';

// Hierarchical category selector (same pattern as Categories page)
interface CatOption { id: number; label: string }
function buildCatOptions(nodes: CategoryTree[], depth = 0): CatOption[] {
  const opts: CatOption[] = [];
  for (const node of nodes) {
    opts.push({ id: node.id, label: '  '.repeat(depth) + node.name });
    opts.push(...buildCatOptions(node.children, depth + 1));
  }
  return opts;
}

const emptyForm = (): PartRequest => ({
  partNumber: '',
  name: '',
  description: '',
  manufacturer: '',
  datasheetUrl: '',
  specs: {},
  categoryId: null,
});

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
    return (
      <div className="mb-4">
        <label className="block text-sm font-medium text-gray-700">
          {spec.name}{spec.unit ? ` (${spec.unit})` : ''}
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

export default function PartsPage() {
  const [parts, setParts] = useState<Part[]>([]);
  const [categories, setCategories] = useState<Category[]>([]);
  const [categoryTree, setCategoryTree] = useState<CategoryTree[]>([]);
  const [search, setSearch] = useState('');
  const [filterCategoryId, setFilterCategoryId] = useState<number | undefined>();
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [modalOpen, setModalOpen] = useState(false);
  const [editing, setEditing] = useState<Part | null>(null);
  const [form, setForm] = useState<PartRequest>(emptyForm());
  const [specValues, setSpecValues] = useState<Record<string, string>>({});
  const [specDefs, setSpecDefs] = useState<SpecDefinition[]>([]);
  const [saving, setSaving] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);

  const loadParts = (s?: string, cid?: number) => {
    getParts(s, cid)
      .then(setParts)
      .catch((e: Error) => setError(e.message));
  };

  useEffect(() => {
    setLoading(true);
    Promise.all([getParts(), getCategories(), getCategoryTree()])
      .then(([p, c, t]) => {
        setParts(p);
        setCategories(c);
        setCategoryTree(t);
      })
      .catch((e: Error) => setError(e.message))
      .finally(() => setLoading(false));
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
            next[def.name] = prev[def.name] ?? '';
          }
          return next;
        });
      })
      .catch(() => setSpecDefs([]));
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [form.categoryId, modalOpen]);

  const handleSearch = (e: React.FormEvent) => {
    e.preventDefault();
    loadParts(search || undefined, filterCategoryId);
  };

  const openCreate = () => {
    setEditing(null);
    const f = emptyForm();
    setForm(f);
    setSpecValues({});
    setSpecDefs([]);
    setFormError(null);
    setModalOpen(true);
  };

  const openEdit = (part: Part) => {
    setEditing(part);
    const f: PartRequest = {
      partNumber: part.partNumber,
      name: part.name,
      description: part.description ?? '',
      manufacturer: part.manufacturer ?? '',
      datasheetUrl: part.datasheetUrl ?? '',
      specs: part.specs ?? {},
      categoryId: part.categoryId ?? null,
    };
    setForm(f);
    // Populate spec values from existing part specs; spec defs will be fetched by the effect
    const existing: Record<string, string> = {};
    for (const [k, v] of Object.entries(part.specs ?? {})) {
      existing[k] = String(v);
    }
    setSpecValues(existing);
    setFormError(null);
    setModalOpen(true);
  };

  const handleSave = async () => {
    setSaving(true);
    setFormError(null);
    // Only include spec values for keys present in current spec definitions
    const filteredSpecs: Record<string, string> = {};
    for (const def of specDefs) {
      if (specValues[def.name] !== undefined && specValues[def.name] !== '') {
        filteredSpecs[def.name] = specValues[def.name];
      }
    }
    const payload: PartRequest = { ...form, specs: filteredSpecs };
    try {
      if (editing) {
        await updatePart(editing.id, payload);
      } else {
        await createPart(payload);
      }
      setModalOpen(false);
      loadParts(search || undefined, filterCategoryId);
    } catch (e: unknown) {
      setFormError((e as Error).message);
    } finally {
      setSaving(false);
    }
  };

  const handleDelete = async (part: Part) => {
    if (!confirm(`Delete part "${part.name}"?`)) return;
    try {
      await deletePart(part.id);
      loadParts(search || undefined, filterCategoryId);
    } catch (e: unknown) {
      alert((e as Error).message);
    }
  };

  const catOptions = buildCatOptions(categoryTree);

  const columns: Column<Part>[] = [
    {
      key: 'partNumber',
      header: 'Part #',
      render: (row) => (
        <Link to={`/parts/${row.id}`} className="font-mono text-blue-600 hover:underline">
          {row.partNumber}
        </Link>
      ),
    },
    { key: 'name', header: 'Name' },
    { key: 'manufacturer', header: 'Manufacturer', render: (r) => r.manufacturer ?? '—' },
    {
      key: 'category',
      header: 'Category',
      render: (r) => r.categoryBreadcrumb ?? '—',
    },
  ];

  return (
    <div className="p-8">
      <div className="mb-6 flex items-center justify-between">
        <h1 className="text-2xl font-bold text-gray-900">Parts</h1>
        <button
          onClick={openCreate}
          className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700"
        >
          + New Part
        </button>
      </div>

      {/* Search / filter bar */}
      <form onSubmit={handleSearch} className="mb-6 flex gap-3">
        <input
          type="text"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          placeholder="Search by name or part number…"
          className="flex-1 rounded-lg border border-gray-300 px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
        />
        <select
          value={filterCategoryId ?? ''}
          onChange={(e) => setFilterCategoryId(e.target.value ? Number(e.target.value) : undefined)}
          className="rounded-lg border border-gray-300 px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
        >
          <option value="">All categories</option>
          {categories.map((c) => (
            <option key={c.id} value={c.id}>
              {c.breadcrumb}
            </option>
          ))}
        </select>
        <button
          type="submit"
          className="rounded-lg bg-gray-700 px-4 py-2 text-sm font-medium text-white hover:bg-gray-800"
        >
          Search
        </button>
        <button
          type="button"
          onClick={() => {
            setSearch('');
            setFilterCategoryId(undefined);
            loadParts();
          }}
          className="rounded-lg border border-gray-300 px-4 py-2 text-sm hover:bg-gray-50"
        >
          Clear
        </button>
      </form>

      {loading && <p className="text-gray-500">Loading...</p>}
      {error && <p className="text-red-600">{error}</p>}

      {!loading && (
        <DataTable
          columns={columns}
          data={parts}
          keyExtractor={(p) => p.id}
          actions={(part) => (
            <div className="flex justify-end gap-2">
              <button
                onClick={() => openEdit(part)}
                className="rounded px-2 py-1 text-xs text-blue-600 hover:bg-blue-50"
              >
                Edit
              </button>
              <button
                onClick={() => handleDelete(part)}
                className="rounded px-2 py-1 text-xs text-red-600 hover:bg-red-50"
              >
                Delete
              </button>
            </div>
          )}
        />
      )}

      {/* Create/Edit Modal */}
      <Modal
        open={modalOpen}
        onClose={() => setModalOpen(false)}
        title={editing ? 'Edit Part' : 'New Part'}
      >
        <div className="max-h-[70vh] overflow-y-auto pr-1">
          <FormField
            label="Part Number *"
            value={form.partNumber}
            onChange={(e) => setForm({ ...form, partNumber: e.target.value })}
            placeholder="e.g. BC547"
          />
          <FormField
            label="Name *"
            value={form.name}
            onChange={(e) => setForm({ ...form, name: e.target.value })}
            placeholder="e.g. NPN General Purpose Transistor"
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

          {/* Dynamic spec fields */}
          {specDefs.length > 0 ? (
            <div className="mt-2">
              <p className="mb-2 text-sm font-medium text-gray-700">Specifications</p>
              {specDefs.map((spec) => (
                <SpecField
                  key={spec.id}
                  spec={spec}
                  value={specValues[spec.name] ?? ''}
                  onChange={(val) => setSpecValues((prev) => ({ ...prev, [spec.name]: val }))}
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
            disabled={saving || !form.partNumber.trim() || !form.name.trim()}
            className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-50"
          >
            {saving ? 'Saving…' : 'Save'}
          </button>
        </div>
      </Modal>
    </div>
  );
}
