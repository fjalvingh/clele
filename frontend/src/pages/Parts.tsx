import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { createPart, deletePart, getCategories, getCategoryTree, getParts, updatePart } from '../api';
import type { Category, CategoryTree, Part, PartRequest } from '../api/types';
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
  const [specsText, setSpecsText] = useState('{}');
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

  const handleSearch = (e: React.FormEvent) => {
    e.preventDefault();
    loadParts(search || undefined, filterCategoryId);
  };

  const openCreate = () => {
    setEditing(null);
    setForm(emptyForm());
    setSpecsText('{}');
    setFormError(null);
    setModalOpen(true);
  };

  const openEdit = (part: Part) => {
    setEditing(part);
    setForm({
      partNumber: part.partNumber,
      name: part.name,
      description: part.description ?? '',
      manufacturer: part.manufacturer ?? '',
      datasheetUrl: part.datasheetUrl ?? '',
      specs: part.specs ?? {},
      categoryId: part.categoryId ?? null,
    });
    setSpecsText(JSON.stringify(part.specs ?? {}, null, 2));
    setFormError(null);
    setModalOpen(true);
  };

  const handleSave = async () => {
    setSaving(true);
    setFormError(null);
    let parsedSpecs: Record<string, unknown> = {};
    try {
      parsedSpecs = JSON.parse(specsText || '{}');
    } catch {
      setFormError('Specs must be valid JSON');
      setSaving(false);
      return;
    }
    const payload: PartRequest = { ...form, specs: parsedSpecs };
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
          <div className="mb-4">
            <label className="block text-sm font-medium text-gray-700">
              Specs (JSON)
            </label>
            <textarea
              className="mt-1 block w-full rounded-md border border-gray-300 px-3 py-2 font-mono text-xs shadow-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
              rows={4}
              value={specsText}
              onChange={(e) => setSpecsText(e.target.value)}
              placeholder='{"voltage": "5V", "current": "100mA"}'
            />
          </div>
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
