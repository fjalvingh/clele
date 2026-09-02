import { useCallback, useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import {
  createSpecDefinition,
  deleteSpecDefinition,
  getSpecGroup,
  getSpecGroupFields,
  getSpecGroups,
  mergeSpecDefinitions,
  moveSpecDefinitions,
  updateSpecDefinition,
} from '../api';
import type { SpecDefinition, SpecDefinitionRequest, SpecGroup } from '../api/types';
import ConvertToNumberModal from '../components/ConvertToNumberModal';
import FormField from '../components/FormField';
import { NumberField } from '../components/NumberInput';
import Modal from '../components/Modal';

const DATA_TYPES = ['TEXT', 'NUMBER', 'BOOLEAN', 'SELECT'] as const;

// The display-order box is empty while being retyped, so the form holds it as nullable and the
// save handler coerces; SpecDefinitionRequest itself stays strictly numeric.
type SpecFormState = Omit<SpecDefinitionRequest, 'displayOrder'> & { displayOrder: number | null };

const emptyForm = (groupId: number): SpecFormState => ({
  jsonName: '',
  name: '',
  dataType: 'TEXT',
  unit: '',
  metricPrefix: false,
  options: [],
  displayOrder: 0,
  groupId,
  aliases: [],
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
  if (spec.dataType === 'NUMBER' && spec.unit)
    return spec.metricPrefix ? `${spec.unit} (metric)` : spec.unit;
  if (spec.dataType === 'SELECT' && spec.options && spec.options.length > 0)
    return spec.options.join(', ');
  return '—';
}

/**
 * The fields inside one spec group: add, edit, delete, move to another group, and merge the
 * duplicates that different sources produced for the same concept. A merge keeps the losers' JSON
 * names as aliases of the survivor, so later updates from those sources still land on it.
 */
export default function SpecGroupDetailPage() {
  const { groupId } = useParams<{ groupId: string }>();
  const id = Number(groupId);

  const [group, setGroup] = useState<SpecGroup | null>(null);
  const [groups, setGroups] = useState<SpecGroup[]>([]);
  const [specs, setSpecs] = useState<SpecDefinition[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const [selected, setSelected] = useState<number[]>([]);
  const [mergeOpen, setMergeOpen] = useState(false);
  const [mergeTargetId, setMergeTargetId] = useState<number | null>(null);
  const [moveOpen, setMoveOpen] = useState(false);
  const [moveGroupId, setMoveGroupId] = useState<number | null>(null);
  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);

  const [modalOpen, setModalOpen] = useState(false);
  const [editing, setEditing] = useState<SpecDefinition | null>(null);
  const [form, setForm] = useState<SpecFormState>(emptyForm(0));
  const [optionsText, setOptionsText] = useState('');
  const [aliasText, setAliasText] = useState('');
  const [saving, setSaving] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);
  const [converting, setConverting] = useState<SpecDefinition | null>(null);

  const load = useCallback(() => {
    setLoading(true);
    Promise.all([getSpecGroup(id), getSpecGroupFields(id), getSpecGroups()])
      .then(([g, fields, allGroups]) => {
        setGroup(g);
        setSpecs(fields);
        setGroups(allGroups);
        // Drop selections for rows that no longer exist (merged away, moved out, deleted).
        setSelected((sel) => sel.filter((s) => fields.some((f) => f.id === s)));
      })
      .catch((e: Error) => setError(e.message))
      .finally(() => setLoading(false));
  }, [id]);

  useEffect(load, [load]);

  const toggle = (specId: number) =>
    setSelected((sel) =>
      sel.includes(specId) ? sel.filter((s) => s !== specId) : [...sel, specId]
    );

  const toggleAll = () =>
    setSelected((sel) => (sel.length === specs.length ? [] : specs.map((s) => s.id)));

  const openCreate = () => {
    setEditing(null);
    setForm({
      ...emptyForm(id),
      displayOrder: specs.reduce((max, s) => Math.max(max, s.displayOrder), -1) + 1,
    });
    setOptionsText('');
    setAliasText('');
    setFormError(null);
    setModalOpen(true);
  };

  const openEdit = (spec: SpecDefinition) => {
    setEditing(spec);
    setForm({
      jsonName: spec.jsonName,
      name: spec.name,
      dataType: spec.dataType,
      unit: spec.unit ?? '',
      metricPrefix: spec.metricPrefix ?? false,
      options: spec.options ?? [],
      displayOrder: spec.displayOrder,
      groupId: spec.groupId,
      aliases: spec.aliases ?? [],
    });
    setOptionsText(spec.options ? spec.options.join(', ') : '');
    setAliasText((spec.aliases ?? []).join(', '));
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
    const aliases = aliasText.split(',').map((s) => s.trim()).filter(Boolean);

    const unit = form.dataType === 'NUMBER' ? (form.unit ?? '') : '';
    // Metric scaling only applies to a NUMBER spec with a single base unit.
    const isSingleUnit = unit.trim() !== '' && !unit.includes(',');
    const payload: SpecDefinitionRequest = {
      ...form,
      displayOrder: form.displayOrder ?? 0,
      unit,
      metricPrefix: isSingleUnit ? !!form.metricPrefix : false,
      options: parsedOptions,
      aliases,
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

  const openMerge = () => {
    setMergeTargetId(selected[0] ?? null);
    setActionError(null);
    setMergeOpen(true);
  };

  const handleMerge = async () => {
    if (mergeTargetId == null) return;
    setBusy(true);
    setActionError(null);
    try {
      await mergeSpecDefinitions({
        targetId: mergeTargetId,
        sourceIds: selected.filter((s) => s !== mergeTargetId),
      });
      setMergeOpen(false);
      setSelected([]);
      load();
    } catch (e: unknown) {
      setActionError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  const openMove = () => {
    setMoveGroupId(groups.find((g) => g.id !== id)?.id ?? null);
    setActionError(null);
    setMoveOpen(true);
  };

  const handleMove = async () => {
    if (moveGroupId == null) return;
    setBusy(true);
    setActionError(null);
    try {
      await moveSpecDefinitions({ specIds: selected, groupId: moveGroupId });
      setMoveOpen(false);
      setSelected([]);
      load();
    } catch (e: unknown) {
      setActionError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  const selectedSpecs = specs.filter((s) => selected.includes(s.id));

  return (
    <div className="p-4 md:p-8">
      <nav className="mb-4 text-sm text-gray-500">
        <Link to="/specs" className="hover:underline">
          Spec Fields
        </Link>{' '}
        / <span className="font-medium text-gray-800">{group?.name ?? '…'}</span>
      </nav>

      <div className="mb-6 flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-gray-900">{group?.name ?? 'Spec group'}</h1>
          <p className="mt-1 text-sm text-gray-500">
            {group?.description || 'The specification fields in this group.'}
          </p>
        </div>
        <div className="flex gap-3">
          <button
            onClick={openMove}
            disabled={selected.length === 0 || groups.length < 2}
            className="rounded-lg border border-gray-300 px-4 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50 disabled:opacity-50"
          >
            Move to group{selected.length > 0 ? ` (${selected.length})` : ''}
          </button>
          <button
            onClick={openMerge}
            disabled={selected.length < 2}
            className="rounded-lg border border-gray-300 px-4 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50 disabled:opacity-50"
          >
            Merge selected{selected.length > 0 ? ` (${selected.length})` : ''}
          </button>
          <button
            onClick={openCreate}
            className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700"
          >
            + New Spec Field
          </button>
        </div>
      </div>

      {loading && <p className="text-gray-500">Loading...</p>}
      {error && <p className="text-red-600">{error}</p>}

      {!loading && !error && (
        <div className="overflow-hidden rounded-xl border border-gray-200 bg-surface shadow-sm">
          {specs.length === 0 ? (
            <p className="p-6 text-sm text-gray-400">
              No spec fields in this group yet. Create one, or move fields here from another group.
            </p>
          ) : (
            <div className="overflow-x-auto">
              <table className="min-w-full divide-y divide-gray-200 text-sm">
                <thead className="bg-gray-50">
                  <tr>
                    <th className="w-10 px-4 py-3">
                      <input
                        type="checkbox"
                        checked={selected.length === specs.length}
                        onChange={toggleAll}
                        className="rounded border-gray-300 text-blue-600"
                        aria-label="Select all spec fields"
                      />
                    </th>
                    <th className="px-4 py-3 text-left font-medium text-gray-500">JSON Name</th>
                    <th className="px-4 py-3 text-left font-medium text-gray-500">Title</th>
                    <th className="px-4 py-3 text-left font-medium text-gray-500">Also known as</th>
                    <th className="px-4 py-3 text-left font-medium text-gray-500">Type</th>
                    <th className="px-4 py-3 text-left font-medium text-gray-500">Unit / Options</th>
                    <th className="px-4 py-3 text-left font-medium text-gray-500">Order</th>
                    <th className="px-4 py-3" />
                  </tr>
                </thead>
                <tbody className="divide-y divide-gray-100">
                  {specs.map((spec) => (
                    <tr
                      key={spec.id}
                      className={selected.includes(spec.id) ? 'bg-blue-50/60' : 'hover:bg-gray-50'}
                    >
                      <td className="px-4 py-3">
                        <input
                          type="checkbox"
                          checked={selected.includes(spec.id)}
                          onChange={() => toggle(spec.id)}
                          className="rounded border-gray-300 text-blue-600"
                          aria-label={`Select ${spec.name}`}
                        />
                      </td>
                      <td className="px-4 py-3 font-mono text-xs text-gray-500">{spec.jsonName}</td>
                      <td className="px-4 py-3 font-medium text-gray-900">{spec.name}</td>
                      <td className="px-4 py-3 font-mono text-xs text-gray-500">
                        {spec.aliases && spec.aliases.length > 0 ? spec.aliases.join(', ') : '—'}
                      </td>
                      <td className="px-4 py-3">
                        <span className="rounded-full bg-blue-100 px-2 py-0.5 text-xs text-blue-700">
                          {typeLabel(spec.dataType)}
                        </span>
                      </td>
                      <td className="px-4 py-3 text-gray-600">{unitOrOptions(spec)}</td>
                      <td className="px-4 py-3 text-gray-500">{spec.displayOrder}</td>
                      <td className="px-4 py-3">
                        <div className="flex justify-end gap-2">
                          {spec.dataType === 'TEXT' && (
                            <button
                              onClick={() => setConverting(spec)}
                              className="rounded px-2 py-1 text-xs text-emerald-700 hover:bg-emerald-50"
                              title="Convert this text field to a numeric field"
                            >
                              → Number
                            </button>
                          )}
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
            </div>
          )}
        </div>
      )}

      {/* Merge — pick which of the selected fields survives. */}
      <Modal open={mergeOpen} onClose={() => setMergeOpen(false)} title="Merge spec fields">
        <p className="mb-3 text-sm text-gray-600">
          Keep one field and fold the others into it. The others' JSON names become aliases of the
          survivor, so updates from the sources that use them keep landing on it, and every part's
          value is re-keyed onto the survivor.
        </p>
        <div className="mb-4 space-y-2">
          {selectedSpecs.map((spec) => (
            <label key={spec.id} className="flex cursor-pointer items-start gap-2">
              <input
                type="radio"
                name="merge-target"
                checked={mergeTargetId === spec.id}
                onChange={() => setMergeTargetId(spec.id)}
                className="mt-1 border-gray-300 text-blue-600"
              />
              <span className="text-sm">
                <span className="font-medium text-gray-900">{spec.name}</span>{' '}
                <span className="font-mono text-xs text-gray-500">({spec.jsonName})</span>
                <span className="block text-xs text-gray-500">
                  {typeLabel(spec.dataType)} · {unitOrOptions(spec)}
                </span>
              </span>
            </label>
          ))}
        </div>
        <p className="mb-3 text-xs text-gray-500">
          Where a part has values for more than one of these, the survivor's own value is kept.
        </p>
        {actionError && <p className="mb-3 text-sm text-red-600">{actionError}</p>}
        <div className="flex justify-end gap-3">
          <button
            onClick={() => setMergeOpen(false)}
            className="rounded-lg border border-gray-300 px-4 py-2 text-sm hover:bg-gray-50"
          >
            Cancel
          </button>
          <button
            onClick={handleMerge}
            disabled={busy || mergeTargetId == null}
            className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-50"
          >
            {busy ? 'Merging…' : `Merge ${selected.length} fields`}
          </button>
        </div>
      </Modal>

      {/* Move the selected fields to another group. */}
      <Modal open={moveOpen} onClose={() => setMoveOpen(false)} title="Move to group">
        <FormField
          as="select"
          label={`Move ${selected.length} spec field(s) to`}
          value={moveGroupId ?? ''}
          onChange={(e) => setMoveGroupId(Number(e.target.value))}
        >
          {groups
            .filter((g) => g.id !== id)
            .map((g) => (
              <option key={g.id} value={g.id}>
                {g.name}
              </option>
            ))}
        </FormField>
        {actionError && <p className="mb-3 text-sm text-red-600">{actionError}</p>}
        <div className="flex justify-end gap-3">
          <button
            onClick={() => setMoveOpen(false)}
            className="rounded-lg border border-gray-300 px-4 py-2 text-sm hover:bg-gray-50"
          >
            Cancel
          </button>
          <button
            onClick={handleMove}
            disabled={busy || moveGroupId == null}
            className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-50"
          >
            {busy ? 'Moving…' : 'Move'}
          </button>
        </div>
      </Modal>

      <Modal
        open={modalOpen}
        onClose={() => setModalOpen(false)}
        title={editing ? 'Edit Spec Field' : 'New Spec Field'}
      >
        <FormField
          label="JSON Name *"
          value={form.jsonName}
          onChange={(e) => setForm({ ...form, jsonName: e.target.value })}
          placeholder="e.g. supply_voltage — exact key inside part.specs"
        />
        <FormField
          label="Title *"
          value={form.name}
          onChange={(e) => setForm({ ...form, name: e.target.value })}
          placeholder="e.g. Package, Voltage Rating"
        />
        <FormField
          as="select"
          label="Group *"
          value={form.groupId ?? id}
          onChange={(e) => setForm({ ...form, groupId: Number(e.target.value) })}
        >
          {groups.map((g) => (
            <option key={g.id} value={g.id}>
              {g.name}
            </option>
          ))}
        </FormField>
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

        {form.dataType === 'NUMBER' &&
          (form.unit ?? '').trim() !== '' &&
          !(form.unit ?? '').includes(',') && (
            <div className="mb-4">
              <label className="flex cursor-pointer items-center gap-2">
                <input
                  type="checkbox"
                  checked={!!form.metricPrefix}
                  onChange={(e) => setForm({ ...form, metricPrefix: e.target.checked })}
                  className="rounded border-gray-300 text-blue-600"
                />
                <span className="text-sm font-medium text-gray-700">
                  Scale with metric prefixes
                </span>
              </label>
              <p className="mt-1 text-xs text-gray-500">
                Value is stored in the base unit ({(form.unit ?? '').trim()}); it's shown as e.g.
                0.009 → 9 m{(form.unit ?? '').trim()}.
              </p>
            </div>
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

        <div className="mb-4">
          <label className="block text-sm font-medium text-gray-700">
            Also known as (comma-separated)
          </label>
          <textarea
            className="mt-1 block w-full rounded-md border border-gray-300 px-3 py-2 font-mono text-sm shadow-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
            rows={2}
            value={aliasText}
            onChange={(e) => setAliasText(e.target.value)}
            placeholder="e.g. vsupply, supply_voltage"
          />
          <p className="mt-1 text-xs text-gray-500">
            The JSON names other sources use for this same spec. Incoming data under any of them is
            stored under the JSON name above.
          </p>
        </div>

        <NumberField
          label="Display Order"
          value={form.displayOrder}
          onChange={(v) => setForm({ ...form, displayOrder: v })}
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
            disabled={saving || !form.jsonName.trim() || !form.name.trim()}
            className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-50"
          >
            {saving ? 'Saving…' : 'Save'}
          </button>
        </div>
      </Modal>

      {converting && (
        <ConvertToNumberModal
          spec={converting}
          onClose={() => setConverting(null)}
          onConverted={() => {
            setConverting(null);
            load();
          }}
        />
      )}
    </div>
  );
}
