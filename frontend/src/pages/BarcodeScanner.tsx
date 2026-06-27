import { useCallback, useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { addStock, findLocalParts, getMyLocations, quickAddPart, searchPartsOnline } from '../api';
import type { Location, Part, PartSearchResult } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import PrintLabelModal from '../components/PrintLabelModal';

type Phase =
  | { kind: 'scan' }
  | { kind: 'searching'; step: 'local' | 'online' }
  | { kind: 'multiple'; parts: Part[] }
  | { kind: 'found-local'; part: Part }
  | { kind: 'found-online'; result: PartSearchResult }
  | { kind: 'not-found'; query: string }
  | { kind: 'adding' };

interface RecentScan {
  id: number;
  barcode: string;
  partNumber: string;
  description?: string;
  qty: number;
}

let nextId = 0;

export default function BarcodeScannerPage() {
  const { user, refresh } = useAuth();
  const inputRef = useRef<HTMLInputElement>(null);

  const [barcode, setBarcode] = useState('');
  const [phase, setPhase] = useState<Phase>({ kind: 'scan' });
  const [locations, setLocations] = useState<Location[]>([]);
  const [locationId, setLocationId] = useState<number | ''>('');
  const [quantity, setQuantity] = useState(1);
  const [unitPrice, setUnitPrice] = useState('');
  const [error, setError] = useState('');
  const [success, setSuccess] = useState('');
  const [recentScans, setRecentScans] = useState<RecentScan[]>([]);
  const [labelPart, setLabelPart] = useState<Part | null>(null);

  useEffect(() => {
    getMyLocations().then(setLocations).catch(() => {});
  }, []);

  useEffect(() => {
    if (user?.lastLocationId && locationId === '') {
      setLocationId(user.lastLocationId);
    }
  }, [user?.lastLocationId]);

  // Re-focus whenever we return to scan phase
  useEffect(() => {
    if (phase.kind === 'scan') {
      inputRef.current?.focus();
    }
  }, [phase.kind]);

  const resetToScan = useCallback((msg?: string) => {
    setBarcode('');
    setQuantity(1);
    setUnitPrice('');
    setError('');
    if (msg) setSuccess(msg);
    setPhase({ kind: 'scan' });
  }, []);

  const pushRecentScan = (bc: string, partNumber: string, description?: string, qty = 1) => {
    setRecentScans((prev) => [
      { id: nextId++, barcode: bc, partNumber, description, qty },
      ...prev.slice(0, 4),
    ]);
  };

  const handleScan = useCallback(async (code: string) => {
    const q = code.trim().replace(/[{}]/g, '');
    if (!q) return;
    setSuccess('');
    setError('');
    setPhase({ kind: 'searching', step: 'local' });

    try {
      const local = await findLocalParts(q);
      if (local.length === 1) {
        setPhase({ kind: 'found-local', part: local[0] });
        return;
      }
      if (local.length > 1) {
        setPhase({ kind: 'multiple', parts: local });
        return;
      }

      setPhase({ kind: 'searching', step: 'online' });
      const online = await searchPartsOnline(q);
      if (online.length > 0) {
        setPhase({ kind: 'found-online', result: online[0] });
        return;
      }

      setPhase({ kind: 'not-found', query: q });
    } catch (e) {
      setError((e as Error).message);
      setPhase({ kind: 'scan' });
    }
  }, []);

  const handleBarcodeKeyDown = (e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'Enter' && barcode.trim()) {
      handleScan(barcode);
    }
  };

  const handleAddToExisting = async (part: Part, printAfter = false) => {
    if (!locationId) { setError('Select a location first'); return; }
    setPhase({ kind: 'adding' });
    try {
      await addStock({
        partId: part.id,
        locationId: locationId as number,
        quantity,
        unitPrice: unitPrice ? parseFloat(unitPrice) : null,
      });
      await refresh();
      pushRecentScan(barcode, part.partNumber, part.description, quantity);
      if (printAfter) {
        setLabelPart(part);
        setBarcode('');
        setQuantity(1);
        setUnitPrice('');
        setError('');
        setSuccess('');
        setPhase({ kind: 'scan' });
      } else {
        resetToScan(`Added ${quantity}× ${part.partNumber}`);
      }
    } catch (e) {
      setError((e as Error).message);
      setPhase({ kind: 'found-local', part });
    }
  };

  const handleCreateAndAdd = async (result: PartSearchResult, printAfter = false) => {
    if (!locationId) { setError('Select a location first'); return; }
    setPhase({ kind: 'adding' });
    try {
      const res = await quickAddPart({
        partNumber: result.mpn || barcode,
        description: result.shortDescription,
        manufacturer: result.manufacturer,
        datasheetUrl: result.datasheetUrl,
        locationId: locationId as number,
        quantity,
        unitPrice: unitPrice ? parseFloat(unitPrice) : null,
      });
      await refresh();
      pushRecentScan(barcode, res.part.partNumber, res.part.description, quantity);
      if (printAfter) {
        setLabelPart(res.part);
        setBarcode('');
        setQuantity(1);
        setUnitPrice('');
        setError('');
        setSuccess('');
        setPhase({ kind: 'scan' });
      } else {
        resetToScan(`Created & added ${quantity}× ${res.part.partNumber}`);
      }
    } catch (e) {
      setError((e as Error).message);
      setPhase({ kind: 'found-online', result });
    }
  };

  // Shared stock form fields
  const StockForm = ({
    onSubmit,
    submitLabel,
    onSubmitAndPrint,
    submitAndPrintLabel,
  }: {
    onSubmit: () => void;
    submitLabel: string;
    onSubmitAndPrint?: () => void;
    submitAndPrintLabel?: string;
  }) => (
    <div className="space-y-3">
      {error && (
        <p className="rounded-md bg-red-50 px-3 py-2 text-sm text-red-700">{error}</p>
      )}
      <div className="grid grid-cols-3 gap-3">
        <div className="col-span-3">
          <label className="mb-1 block text-xs font-medium text-gray-500">Location</label>
          <select
            value={locationId}
            onChange={(e) => setLocationId(e.target.value ? Number(e.target.value) : '')}
            className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
          >
            <option value="">— select location —</option>
            {locations.map((l) => (
              <option key={l.id} value={l.id}>{l.breadcrumb}</option>
            ))}
          </select>
        </div>
        <div>
          <label className="mb-1 block text-xs font-medium text-gray-500">Quantity</label>
          <input
            type="number"
            min={1}
            value={quantity}
            onChange={(e) => setQuantity(Math.max(1, parseInt(e.target.value) || 1))}
            className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
          />
        </div>
        <div className="col-span-2">
          <label className="mb-1 block text-xs font-medium text-gray-500">Unit price</label>
          <input
            type="number"
            min={0}
            step="0.01"
            placeholder="0.00"
            value={unitPrice}
            onChange={(e) => setUnitPrice(e.target.value)}
            className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
          />
        </div>
      </div>
      <div className="flex flex-wrap gap-2 pt-1">
        <button
          onClick={onSubmit}
          disabled={!locationId || !unitPrice}
          className="flex-1 rounded-md bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:cursor-not-allowed disabled:opacity-50"
        >
          {submitLabel}
        </button>
        {onSubmitAndPrint && (
          <button
            onClick={onSubmitAndPrint}
            disabled={!locationId || !unitPrice}
            className="flex-1 rounded-md bg-indigo-600 px-4 py-2 text-sm font-medium text-white hover:bg-indigo-700 disabled:cursor-not-allowed disabled:opacity-50"
          >
            {submitAndPrintLabel ?? 'Add & Print Label'}
          </button>
        )}
        <button
          onClick={() => resetToScan()}
          className="rounded-md border border-gray-300 px-4 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50"
        >
          Skip
        </button>
      </div>
    </div>
  );

  return (
    <div className="min-h-full bg-gray-50 p-6">
      <div className="mx-auto max-w-xl space-y-5">

        {/* Header */}
        <div>
          <h1 className="text-xl font-semibold text-gray-900">Barcode Scanner</h1>
          <p className="mt-0.5 text-sm text-gray-500">
            Scan a barcode or type a part number, then confirm stock details.
          </p>
        </div>

        {/* Scan input — always visible */}
        <div className="rounded-xl border border-gray-200 bg-white p-5 shadow-sm">
          <label className="mb-2 block text-sm font-medium text-gray-700">
            Barcode / Part number
          </label>
          <div className="flex gap-2">
            <input
              ref={inputRef}
              type="text"
              value={barcode}
              onChange={(e) => setBarcode(e.target.value)}
              onKeyDown={handleBarcodeKeyDown}
              placeholder="Scan or type, then press Enter…"
              disabled={phase.kind === 'searching' || phase.kind === 'adding'}
              autoFocus
              autoComplete="off"
              data-1p-ignore
              data-lpignore="true"
              data-form-type="other"
              spellCheck={false}
              className="flex-1 rounded-md border border-gray-300 px-3 py-2.5 text-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500 disabled:bg-gray-50 disabled:text-gray-400"
            />
            <button
              onClick={() => barcode.trim() && handleScan(barcode)}
              disabled={!barcode.trim() || phase.kind === 'searching' || phase.kind === 'adding'}
              className="rounded-md bg-blue-600 px-4 py-2.5 text-sm font-medium text-white hover:bg-blue-700 disabled:cursor-not-allowed disabled:opacity-50"
            >
              Search
            </button>
          </div>
          {success && (
            <p className="mt-2 text-sm font-medium text-green-600">{success}</p>
          )}
        </div>

        {/* Phase-specific content */}

        {(phase.kind === 'searching' || phase.kind === 'adding') && (
          <div className="flex items-center gap-3 rounded-xl border border-gray-200 bg-white p-5 shadow-sm">
            <svg className="h-5 w-5 animate-spin text-blue-500" viewBox="0 0 24 24" fill="none">
              <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
              <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8v4a4 4 0 00-4 4H4z" />
            </svg>
            <span className="text-sm text-gray-600">
              {phase.kind === 'adding'
                ? 'Saving…'
                : phase.step === 'local'
                ? 'Searching local database…'
                : 'Searching online…'}
            </span>
          </div>
        )}

        {phase.kind === 'multiple' && (
          <div className="rounded-xl border border-gray-200 bg-white shadow-sm">
            <div className="border-b border-gray-100 px-5 py-3">
              <p className="text-sm font-medium text-gray-700">
                {phase.parts.length} matching parts found — select one:
              </p>
            </div>
            <ul className="divide-y divide-gray-100">
              {phase.parts.map((p) => (
                <li key={p.id}>
                  <button
                    onClick={() => setPhase({ kind: 'found-local', part: p })}
                    className="flex w-full items-start gap-3 px-5 py-3 text-left hover:bg-gray-50"
                  >
                    <div className="flex-1 min-w-0">
                      <p className="text-sm font-medium text-gray-900">{p.partNumber}</p>
                      {p.description && (
                        <p className="truncate text-xs text-gray-500">{p.description}</p>
                      )}
                    </div>
                    <span className="shrink-0 text-xs text-gray-400">
                      {p.totalQuantity ?? 0} on hand
                    </span>
                  </button>
                </li>
              ))}
            </ul>
            <div className="border-t border-gray-100 px-5 py-3">
              <button
                onClick={() => resetToScan()}
                className="text-sm text-gray-500 hover:text-gray-700"
              >
                Cancel
              </button>
            </div>
          </div>
        )}

        {phase.kind === 'found-local' && (
          <div className="rounded-xl border border-blue-200 bg-white shadow-sm">
            <div className="border-b border-gray-100 bg-blue-50/50 px-5 py-3">
              <div className="flex items-center justify-between">
                <span className="text-xs font-semibold uppercase tracking-wide text-blue-600">
                  Found in inventory
                </span>
                <Link
                  to={`/parts/${phase.part.id}`}
                  className="text-xs text-blue-600 hover:underline"
                >
                  View part →
                </Link>
              </div>
            </div>
            <div className="space-y-4 p-5">
              <div>
                <p className="text-base font-semibold text-gray-900">{phase.part.partNumber}</p>
                {phase.part.description && (
                  <p className="mt-0.5 text-sm text-gray-600">{phase.part.description}</p>
                )}
                <div className="mt-1.5 flex flex-wrap gap-3 text-xs text-gray-500">
                  {phase.part.manufacturer && <span>{phase.part.manufacturer}</span>}
                  {phase.part.categoryName && <span>{phase.part.categoryName}</span>}
                  <span className="font-medium text-gray-700">
                    {phase.part.totalQuantity ?? 0} on hand
                  </span>
                </div>
              </div>
              <StockForm
                onSubmit={() => handleAddToExisting(phase.part)}
                submitLabel="Add Stock"
                onSubmitAndPrint={() => handleAddToExisting(phase.part, true)}
                submitAndPrintLabel="Add & Print Label"
              />
            </div>
          </div>
        )}

        {phase.kind === 'found-online' && (
          <div className="rounded-xl border border-emerald-200 bg-white shadow-sm">
            <div className="border-b border-gray-100 bg-emerald-50/50 px-5 py-3">
              <span className="text-xs font-semibold uppercase tracking-wide text-emerald-600">
                New part — found online
              </span>
            </div>
            <div className="space-y-4 p-5">
              <div>
                <p className="text-base font-semibold text-gray-900">
                  {phase.result.mpn || barcode}
                </p>
                {phase.result.shortDescription && (
                  <p className="mt-0.5 text-sm text-gray-600">{phase.result.shortDescription}</p>
                )}
                <div className="mt-1.5 flex flex-wrap gap-3 text-xs text-gray-500">
                  {phase.result.manufacturer && <span>{phase.result.manufacturer}</span>}
                  {phase.result.category && <span>{phase.result.category}</span>}
                </div>
                {phase.result.specs && phase.result.specs.length > 0 && (
                  <div className="mt-2 flex flex-wrap gap-1.5">
                    {phase.result.specs.slice(0, 6).map((s) => (
                      <span
                        key={s}
                        className="rounded-full bg-gray-100 px-2 py-0.5 text-xs text-gray-600"
                      >
                        {s}
                      </span>
                    ))}
                  </div>
                )}
              </div>
              <StockForm
                onSubmit={() => handleCreateAndAdd(phase.result)}
                submitLabel="Create & Add Stock"
                onSubmitAndPrint={() => handleCreateAndAdd(phase.result, true)}
                submitAndPrintLabel="Create, Add & Print Label"
              />
            </div>
          </div>
        )}

        {phase.kind === 'not-found' && (
          <div className="rounded-xl border border-gray-200 bg-white p-5 shadow-sm">
            <p className="text-sm font-medium text-gray-700">
              No part found for <span className="font-mono text-gray-900">{phase.query}</span>
            </p>
            <p className="mt-1 text-sm text-gray-500">
              The barcode was not in the local database or in online search results.
            </p>
            <div className="mt-4 flex gap-3">
              <Link
                to="/quick-add"
                className="rounded-md bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700"
              >
                Add via Quick Add
              </Link>
              <button
                onClick={() => resetToScan()}
                className="rounded-md border border-gray-300 px-4 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50"
              >
                Scan next
              </button>
            </div>
          </div>
        )}

        {labelPart && (
          <PrintLabelModal
            open={true}
            onClose={() => {
              setLabelPart(null);
              setSuccess(`Added ${labelPart.partNumber}`);
            }}
            part={labelPart}
          />
        )}

        {/* Recent scans */}
        {recentScans.length > 0 && (
          <div className="rounded-xl border border-gray-200 bg-white shadow-sm">
            <div className="border-b border-gray-100 px-5 py-3">
              <p className="text-xs font-semibold uppercase tracking-wide text-gray-400">
                Recent scans this session
              </p>
            </div>
            <ul className="divide-y divide-gray-100">
              {recentScans.map((s) => (
                <li key={s.id} className="flex items-center gap-3 px-5 py-2.5 text-sm">
                  <span className="shrink-0 rounded-full bg-green-100 px-2 py-0.5 text-xs font-medium text-green-700">
                    +{s.qty}
                  </span>
                  <span className="font-medium text-gray-900">{s.partNumber}</span>
                  {s.description && (
                    <span className="min-w-0 flex-1 truncate text-xs text-gray-500">
                      {s.description}
                    </span>
                  )}
                </li>
              ))}
            </ul>
          </div>
        )}
      </div>
    </div>
  );
}
