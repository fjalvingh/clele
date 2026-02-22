import { useEffect, useState } from 'react';
import {
  createCategory,
  deleteCategory,
  getCategories,
  getCategoryTree,
  getSpecDefinitions,
  updateCategory,
} from '../api';
import type { Category, CategoryRequest, CategoryTree, SpecDefinition } from '../api/types';
import FormField from '../components/FormField';
import Modal from '../components/Modal';

// ---- Tree node component ----
interface TreeNodeProps {
  node: CategoryTree;
  onEdit: (cat: Category) => void;
  onDelete: (cat: Category) => void;
  categories: Category[];
}

function TreeNode({ node, onEdit, onDelete, categories }: TreeNodeProps) {
  const [expanded, setExpanded] = useState(true);
  const hasChildren = node.children && node.children.length > 0;

  const fullCat = categories.find((c) => c.id === node.id);

  return (
    <div className="ml-4">
      <div className="flex items-center gap-2 rounded px-2 py-1 hover:bg-gray-50 group">
        <button
          className="w-4 text-gray-400 text-xs"
          onClick={() => setExpanded(!expanded)}
        >
          {hasChildren ? (expanded ? '▼' : '▶') : '•'}
        </button>
        <span className="flex-1 text-sm text-gray-800 font-medium">{node.name}</span>
        {node.description && (
          <span className="hidden text-xs text-gray-400 group-hover:inline">{node.description}</span>
        )}
        <div className="flex gap-1 opacity-0 group-hover:opacity-100 transition-opacity">
          <button
            onClick={() => fullCat && onEdit(fullCat)}
            className="rounded px-2 py-0.5 text-xs text-blue-600 hover:bg-blue-50"
          >
            Edit
          </button>
          <button
            onClick={() => fullCat && onDelete(fullCat)}
            className="rounded px-2 py-0.5 text-xs text-red-600 hover:bg-red-50"
          >
            Delete
          </button>
        </div>
      </div>
      {hasChildren && expanded && (
        <div className="border-l border-gray-200 ml-2">
          {node.children.map((child) => (
            <TreeNode
              key={child.id}
              node={child}
              onEdit={onEdit}
              onDelete={onDelete}
              categories={categories}
            />
          ))}
        </div>
      )}
    </div>
  );
}

// ---- Hierarchical parent selector ----
interface ParentOption {
  id: number;
  label: string;
}

function buildParentOptions(
  nodes: CategoryTree[],
  excludeId: number | null,
  depth = 0
): ParentOption[] {
  const options: ParentOption[] = [];
  for (const node of nodes) {
    if (node.id === excludeId) continue;
    options.push({ id: node.id, label: '  '.repeat(depth) + node.name });
    options.push(...buildParentOptions(node.children, excludeId, depth + 1));
  }
  return options;
}

// ---- Main page ----
export default function CategoriesPage() {
  const [tree, setTree] = useState<CategoryTree[]>([]);
  const [categories, setCategories] = useState<Category[]>([]);
  const [allSpecs, setAllSpecs] = useState<SpecDefinition[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [modalOpen, setModalOpen] = useState(false);
  const [editing, setEditing] = useState<Category | null>(null);
  const [form, setForm] = useState<CategoryRequest>({ name: '', description: '', parentId: null, specIds: [] });
  const [saving, setSaving] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);

  const load = () => {
    setLoading(true);
    Promise.all([getCategoryTree(), getCategories(), getSpecDefinitions()])
      .then(([t, c, s]) => {
        setTree(t);
        setCategories(c);
        setAllSpecs(s);
      })
      .catch((e: Error) => setError(e.message))
      .finally(() => setLoading(false));
  };

  useEffect(load, []);

  const openCreate = () => {
    setEditing(null);
    setForm({ name: '', description: '', parentId: null, specIds: [] });
    setFormError(null);
    setModalOpen(true);
  };

  const openEdit = (cat: Category) => {
    setEditing(cat);
    setForm({
      name: cat.name,
      description: cat.description ?? '',
      parentId: cat.parentId ?? null,
      specIds: cat.specIds ?? [],
    });
    setFormError(null);
    setModalOpen(true);
  };

  const handleSave = async () => {
    setSaving(true);
    setFormError(null);
    try {
      if (editing) {
        await updateCategory(editing.id, form);
      } else {
        await createCategory(form);
      }
      setModalOpen(false);
      load();
    } catch (e: unknown) {
      setFormError((e as Error).message);
    } finally {
      setSaving(false);
    }
  };

  const handleDelete = async (cat: Category) => {
    if (!confirm(`Delete category "${cat.name}"?`)) return;
    try {
      await deleteCategory(cat.id);
      load();
    } catch (e: unknown) {
      alert((e as Error).message);
    }
  };

  const toggleSpecId = (specId: number) => {
    const current = form.specIds ?? [];
    const next = current.includes(specId)
      ? current.filter((id) => id !== specId)
      : [...current, specId];
    setForm({ ...form, specIds: next });
  };

  const parentOptions = buildParentOptions(tree, editing?.id ?? null);

  return (
    <div className="p-8">
      <div className="mb-6 flex items-center justify-between">
        <h1 className="text-2xl font-bold text-gray-900">Categories</h1>
        <button
          onClick={openCreate}
          className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700"
        >
          + New Category
        </button>
      </div>

      {loading && <p className="text-gray-500">Loading...</p>}
      {error && <p className="text-red-600">{error}</p>}

      {!loading && (
        <div className="rounded-xl border border-gray-200 bg-white p-4 shadow-sm">
          {tree.length === 0 ? (
            <p className="text-sm text-gray-400">No categories yet. Create one to get started.</p>
          ) : (
            tree.map((root) => (
              <TreeNode
                key={root.id}
                node={root}
                onEdit={openEdit}
                onDelete={handleDelete}
                categories={categories}
              />
            ))
          )}
        </div>
      )}

      <Modal
        open={modalOpen}
        onClose={() => setModalOpen(false)}
        title={editing ? 'Edit Category' : 'New Category'}
      >
        <FormField
          label="Name *"
          value={form.name}
          onChange={(e) => setForm({ ...form, name: e.target.value })}
          placeholder="e.g. Resistors"
        />
        <FormField
          as="textarea"
          label="Description"
          value={form.description ?? ''}
          onChange={(e) => setForm({ ...form, description: e.target.value })}
          rows={2}
        />
        <FormField
          as="select"
          label="Parent Category"
          value={form.parentId ?? ''}
          onChange={(e) =>
            setForm({
              ...form,
              parentId: e.target.value ? Number(e.target.value) : null,
            })
          }
        >
          <option value="">— None (root) —</option>
          {parentOptions.map((opt) => (
            <option key={opt.id} value={opt.id}>
              {opt.label}
            </option>
          ))}
        </FormField>

        {allSpecs.length > 0 && (
          <div className="mb-4">
            <label className="block text-sm font-medium text-gray-700 mb-1">
              Spec Fields
            </label>
            <p className="text-xs text-gray-400 mb-2">
              Parts in this category will show these fields. Child categories inherit parent specs.
            </p>
            <div className="max-h-40 overflow-y-auto rounded-md border border-gray-200 p-2 space-y-1">
              {allSpecs.map((spec) => {
                const checked = (form.specIds ?? []).includes(spec.id);
                return (
                  <label
                    key={spec.id}
                    className="flex items-center gap-2 cursor-pointer rounded px-2 py-1 hover:bg-gray-50"
                  >
                    <input
                      type="checkbox"
                      checked={checked}
                      onChange={() => toggleSpecId(spec.id)}
                      className="rounded border-gray-300 text-blue-600"
                    />
                    <span className="text-sm text-gray-800">{spec.name}</span>
                    <span className="ml-auto rounded bg-gray-100 px-1.5 py-0.5 text-xs text-gray-500">
                      {spec.dataType.toLowerCase()}
                      {spec.unit ? ` (${spec.unit})` : ''}
                    </span>
                  </label>
                );
              })}
            </div>
          </div>
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
