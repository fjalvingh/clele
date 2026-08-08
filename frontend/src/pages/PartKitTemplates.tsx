import { useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import {
  deletePartKitTemplate,
  generatePartsFromKit,
  getMyLocations,
  getPartKitTemplates,
} from '../api';
import type { Location, PartKitGenerateResult, PartKitTemplate } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import Modal from '../components/Modal';

/** The "Generate parts" dialog: how many of each value came in the pack, and where they go. */
function GenerateModal({
  template,
  locations,
  defaultLocationId,
  onClose,
  onDone,
}: {
  template: PartKitTemplate;
  locations: Location[];
  defaultLocationId: number | null;
  onClose: () => void;
  onDone: () => void;
}) {
  const [quantity, setQuantity] = useState('1');
  const [locationId, setLocationId] = useState<number | ''>(defaultLocationId ?? '');
  const [unitPrice, setUnitPrice] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [result, setResult] = useState<PartKitGenerateResult | null>(null);

  const qty = Number(quantity);
  const valid = locationId !== '' && Number.isFinite(qty) && qty >= 0;

  const run = async () => {
    if (locationId === '') return;
    setBusy(true);
    setError(null);
    try {
      const res = await generatePartsFromKit(template.id, {
        quantityPerValue: qty,
        locationId,
        unitPrice: unitPrice.trim() === '' ? null : Number(unitPrice),
      });
      setResult(res);
      onDone();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Modal open onClose={onClose} title={`Generate parts — ${template.name}`} wide>
      {result ? (
        <div>
          <p className="mb-4 text-sm text-gray-700">
            <span className="font-medium">{result.partsCreated}</span> part
            {result.partsCreated === 1 ? '' : 's'} created,{' '}
            <span className="font-medium">{result.partsFound}</span> already existed,{' '}
            <span className="font-medium">{result.stockAdded}</span> units added.
          </p>
          <div className="max-h-80 overflow-y-auto rounded-md border border-gray-200">
            <table className="min-w-full divide-y divide-gray-200 text-sm">
              <thead className="bg-gray-50">
                <tr>
                  <th className="px-3 py-2 text-left text-xs font-medium uppercase tracking-wider text-gray-500">Value</th>
                  <th className="px-3 py-2 text-left text-xs font-medium uppercase tracking-wider text-gray-500">Part</th>
                  <th className="px-3 py-2 text-left text-xs font-medium uppercase tracking-wider text-gray-500">Result</th>
                  <th className="px-3 py-2 text-right text-xs font-medium uppercase tracking-wider text-gray-500">Added</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-100">
                {result.lines.map((l) => (
                  <tr key={l.value}>
                    <td className="px-3 py-2 font-mono text-gray-700">{l.value}</td>
                    <td className="px-3 py-2">
                      <Link
                        to={`/parts/${l.partId}`}
                        target="_blank"
                        className="font-mono text-blue-600 hover:underline"
                      >
                        {l.partNumber}
                      </Link>
                    </td>
                    <td className="px-3 py-2">
                      <span
                        className={`rounded px-2 py-0.5 text-xs ${
                          l.created
                            ? 'bg-green-500/15 text-green-700'
                            : 'bg-gray-500/15 text-gray-600'
                        }`}
                      >
                        {l.created ? 'created' : 'existing'}
                      </span>
                    </td>
                    <td className="px-3 py-2 text-right text-gray-700">{l.quantityAdded}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <div className="flex justify-end pt-4">
            <button
              onClick={onClose}
              className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700"
            >
              Close
            </button>
          </div>
        </div>
      ) : (
        <div>
          <p className="mb-4 text-sm text-gray-600">
            This creates or finds one part for each of the{' '}
            <span className="font-medium">{template.values.length}</span> value
            {template.values.length === 1 ? '' : 's'} in the kit, and adds the quantity below to each.
            Parts that already exist keep their current fields — only stock is added.
          </p>

          <label className="block text-sm font-medium text-gray-700">Quantity per value *</label>
          <input
            type="number"
            min={0}
            value={quantity}
            onChange={(e) => setQuantity(e.target.value)}
            className="mb-4 mt-1 block w-full rounded-md border border-gray-300 px-3 py-2 text-sm shadow-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
          />

          <label className="block text-sm font-medium text-gray-700">Location *</label>
          <select
            value={locationId}
            onChange={(e) => setLocationId(e.target.value === '' ? '' : Number(e.target.value))}
            className="mb-4 mt-1 block w-full rounded-md border border-gray-300 px-3 py-2 text-sm shadow-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
          >
            <option value="">— Select a location —</option>
            {locations.map((l) => (
              <option key={l.id} value={l.id}>{l.breadcrumb ?? l.name}</option>
            ))}
          </select>

          <label className="block text-sm font-medium text-gray-700">Unit price (optional)</label>
          <input
            type="number"
            step="any"
            min={0}
            value={unitPrice}
            onChange={(e) => setUnitPrice(e.target.value)}
            className="mt-1 block w-full rounded-md border border-gray-300 px-3 py-2 text-sm shadow-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
          />

          {error && <p className="mt-4 text-sm text-red-600">{error}</p>}

          <div className="flex justify-end gap-3 pt-5">
            <button
              onClick={onClose}
              className="rounded-lg border border-gray-300 px-4 py-2 text-sm hover:bg-gray-50"
            >
              Cancel
            </button>
            <button
              onClick={run}
              disabled={busy || !valid || template.values.length === 0}
              className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-50"
            >
              {busy ? 'Generating…' : `Generate ${template.values.length} part${template.values.length === 1 ? '' : 's'}`}
            </button>
          </div>
        </div>
      )}
    </Modal>
  );
}

export default function PartKitTemplatesPage() {
  const navigate = useNavigate();
  const { user } = useAuth();
  const [templates, setTemplates] = useState<PartKitTemplate[]>([]);
  const [locations, setLocations] = useState<Location[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [generating, setGenerating] = useState<PartKitTemplate | null>(null);
  const [deleteConfirm, setDeleteConfirm] = useState<PartKitTemplate | null>(null);

  const load = () => {
    setLoading(true);
    getPartKitTemplates()
      .then(setTemplates)
      .catch((e: Error) => setError(e.message))
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    load();
    getMyLocations().then(setLocations).catch(() => setLocations([]));
  }, []);

  const handleDelete = async (t: PartKitTemplate) => {
    try {
      await deletePartKitTemplate(t.id);
      setTemplates((prev) => prev.filter((x) => x.id !== t.id));
      setDeleteConfirm(null);
    } catch (e) {
      setError((e as Error).message);
    }
  };

  return (
    <div className="p-4 md:p-8">
      <div className="mb-6 flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-2xl font-bold text-gray-900">Part kits</h1>
          <p className="mt-1 text-sm text-gray-500">
            Templates for packs bought as a set — a resistor kit, a capacitor assortment — where the
            parts differ in one value only.
          </p>
        </div>
        <button
          onClick={() => navigate('/part-kits/new')}
          className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700"
        >
          New kit template
        </button>
      </div>

      {error && (
        <div className="mb-4 rounded-lg border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700">
          {error}
        </div>
      )}

      {loading ? (
        <div className="py-12 text-center text-sm text-gray-400">Loading…</div>
      ) : templates.length === 0 ? (
        <div className="rounded-lg border border-gray-200 bg-surface p-12 text-center text-gray-400">
          <p className="text-sm">
            No kit templates yet. Create one to describe a pack of parts once and generate them all
            at a stroke.
          </p>
        </div>
      ) : (
        <div className="overflow-x-auto rounded-lg border border-gray-200 bg-surface shadow-sm">
          <table className="min-w-full divide-y divide-gray-200">
            <thead className="bg-gray-50">
              <tr>
                <th className="px-6 py-3 text-left text-xs font-medium uppercase tracking-wider text-gray-500">Name</th>
                <th className="px-6 py-3 text-left text-xs font-medium uppercase tracking-wider text-gray-500">Part number</th>
                <th className="px-6 py-3 text-left text-xs font-medium uppercase tracking-wider text-gray-500">Category</th>
                <th className="px-6 py-3 text-left text-xs font-medium uppercase tracking-wider text-gray-500">Values</th>
                <th className="px-6 py-3" />
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100">
              {templates.map((t) => (
                <tr key={t.id} className="hover:bg-gray-50">
                  <td className="px-6 py-4">
                    <Link to={`/part-kits/${t.id}`} className="font-medium text-blue-600 hover:underline">
                      {t.name}
                    </Link>
                    {t.notes && (
                      <p className="mt-0.5 max-w-xs truncate text-xs text-gray-400">{t.notes}</p>
                    )}
                  </td>
                  <td className="px-6 py-4 font-mono text-sm text-gray-700">{t.partNumberTemplate}</td>
                  <td className="px-6 py-4 text-sm text-gray-500">{t.categoryBreadcrumb ?? '—'}</td>
                  <td className="px-6 py-4 text-sm text-gray-700">
                    {t.values.length}
                    {t.values.length > 0 && (
                      <span className="ml-2 text-xs text-gray-400">
                        {t.values.slice(0, 4).join(', ')}
                        {t.values.length > 4 ? ', …' : ''}
                      </span>
                    )}
                  </td>
                  <td className="px-6 py-4 text-right">
                    <div className="flex justify-end gap-2">
                      <button
                        onClick={() => setGenerating(t)}
                        disabled={t.values.length === 0}
                        title={t.values.length === 0 ? 'This template has no values yet' : undefined}
                        className="rounded-lg bg-green-600 px-3 py-1.5 text-xs font-medium text-white hover:bg-green-700 disabled:opacity-40"
                      >
                        Generate parts
                      </button>
                      <button
                        onClick={() => navigate(`/part-kits/${t.id}`)}
                        className="rounded-lg border border-gray-300 px-3 py-1.5 text-xs hover:bg-gray-50"
                      >
                        Edit
                      </button>
                      <button
                        onClick={() => setDeleteConfirm(t)}
                        className="rounded-lg border border-gray-300 px-3 py-1.5 text-xs text-red-600 hover:bg-red-50"
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

      {generating && (
        <GenerateModal
          template={generating}
          locations={locations}
          defaultLocationId={user?.lastLocationId ?? null}
          onClose={() => setGenerating(null)}
          onDone={load}
        />
      )}

      {deleteConfirm && (
        <Modal open onClose={() => setDeleteConfirm(null)} title="Delete kit template">
          <p className="text-sm text-gray-700">
            Delete <span className="font-medium">{deleteConfirm.name}</span>? The parts it has
            already generated are not touched.
          </p>
          <div className="flex justify-end gap-3 pt-5">
            <button
              onClick={() => setDeleteConfirm(null)}
              className="rounded-lg border border-gray-300 px-4 py-2 text-sm hover:bg-gray-50"
            >
              Cancel
            </button>
            <button
              onClick={() => handleDelete(deleteConfirm)}
              className="rounded-lg bg-red-600 px-4 py-2 text-sm font-medium text-white hover:bg-red-700"
            >
              Delete
            </button>
          </div>
        </Modal>
      )}
    </div>
  );
}
