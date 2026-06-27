import { useCallback, useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { addStock, findLocalParts, getMyLocations, quickAddPart, searchPartsOnline } from '../api';
import type { Location, Part, PartSearchResult } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import PrintLabelModal from '../components/PrintLabelModal';

type Phase =
  | { kind: 'scan' }
  | { kind: 'searching'; step: 'local' | 'online'; query: string }
  | { kind: 'multiple'; parts: Part[]; query: string }
  | { kind: 'found-local'; part: Part; query: string }
  | { kind: 'found-online'; result: PartSearchResult; query: string }
  | { kind: 'not-found'; query: string }
  | { kind: 'already-tried'; query: string }
  | { kind: 'adding' };

interface RecentScan {
  id: number;
  barcode: string;
  partNumber: string;
  description?: string;
  qty: number;
}

interface StockFormProps {
  error: string;
  locationId: number | '';
  locations: Location[];
  quantity: number;
  unitPrice: string;
  onLocationChange: (id: number | '') => void;
  onQuantityChange: (q: number) => void;
  onUnitPriceChange: (p: string) => void;
  onSubmit: () => void;
  submitLabel: string;
  onSubmitAndPrint?: () => void;
  submitAndPrintLabel?: string;
  onSkip: () => void;
}

function StockForm({
  error,
  locationId,
  locations,
  quantity,
  unitPrice,
  onLocationChange,
  onQuantityChange,
  onUnitPriceChange,
  onSubmit,
  submitLabel,
  onSubmitAndPrint,
  submitAndPrintLabel,
  onSkip,
}: StockFormProps) {
  return (
    <div className="space-y-3">
      {error && (
        <p className="rounded-md bg-red-50 px-3 py-2 text-sm text-red-700">{error}</p>
      )}
      <div className="grid grid-cols-3 gap-3">
        <div className="col-span-3">
          <label className="mb-1 block text-xs font-medium text-gray-500">Location</label>
          <select
            value={locationId}
            onChange={(e) => onLocationChange(e.target.value ? Number(e.target.value) : '')}
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
            type="text"
            inputMode="numeric"
            pattern="[0-9]*"
            value={quantity || ''}
            onChange={(e) => {
              const v = parseInt(e.target.value.replace(/\D/g, ''), 10);
              onQuantityChange(isNaN(v) ? 0 : v);
            }}
            onBlur={() => onQuantityChange(Math.max(1, quantity))}
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
            onChange={(e) => onUnitPriceChange(e.target.value)}
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
          onClick={onSkip}
          className="rounded-md border border-gray-300 px-4 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50"
        >
          Skip
        </button>
      </div>
    </div>
  );
}

let nextId = 0;

export default function BarcodeScannerPage() {
  const { user, refresh } = useAuth();
  const inputRef = useRef<HTMLInputElement>(null);
  const searchGenRef = useRef(0);
  const lastQueryRef = useRef('');

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
  // Codes that were searched but not yet acted upon (last 10 unique)
  const [triedCodes, setTriedCodes] = useState<string[]>([]);

  useEffect(() => {
    getMyLocations().then(setLocations).catch(() => {});
  }, []);

  useEffect(() => {
    if (user?.lastLocationId && locationId === '') {
      setLocationId(user.lastLocationId);
    }
  }, [user?.lastLocationId]);

  // Keep scanner input focused at all times
  useEffect(() => {
    inputRef.current?.focus();
  }, [phase.kind]);

  const addToTried = (code: string) =>
    setTriedCodes((prev) => [code, ...prev.filter((c) => c !== code)].slice(0, 10));

  const removeFromTried = (code: string) =>
    setTriedCodes((prev) => prev.filter((c) => c !== code));

  const resetToScan = useCallback((msg?: string) => {
    setBarcode('');
    setQuantity(1);
    setUnitPrice('');
    setError('');
    if (msg) setSuccess(msg);
    setPhase({ kind: 'scan' });
  }, []);

  const pushRecentScan = (code: string, partNumber: string, description?: string, qty = 1) => {
    removeFromTried(code);
    setRecentScans((prev) => [
      { id: nextId++, barcode: code, partNumber, description, qty },
      ...prev.slice(0, 4),
    ]);
  };

  const searchOnline = useCallback(async (q: string) => {
    const gen = ++searchGenRef.current;
    setError('');
    setPhase({ kind: 'searching', step: 'online', query: q });
    try {
      const online = await searchPartsOnline(q);
      if (gen !== searchGenRef.current) return;
      if (online.length > 0) {
        setPhase({ kind: 'found-online', result: online[0], query: q });
      } else {
        setPhase({ kind: 'not-found', query: q });
      }
    } catch (e) {
      if (gen !== searchGenRef.current) return;
      setError((e as Error).message);
      setPhase({ kind: 'scan' });
    }
  }, []);

  const handleScan = useCallback(async (code: string) => {
    const q = code.trim().replace(/[{}]/g, '');
    if (!q) return;

    // Reject suspiciously short all-numeric codes
    if (/^\d+$/.test(q) && q.length < 4) {
      setBarcode('');
      setSuccess('');
      setError(`Barcode ${q} too short`);
      setPhase({ kind: 'scan' });
      return;
    }

    // If this code was already searched without being acted on, say so
    if (triedCodes.includes(q)) {
      setBarcode('');
      setSuccess('');
      setError('');
      setPhase({ kind: 'already-tried', query: q });
      return;
    }

    lastQueryRef.current = q;
    const gen = ++searchGenRef.current;
    setSuccess('');
    setError('');
    setPhase({ kind: 'searching', step: 'local', query: q });

    try {
      const local = await findLocalParts(q);
      if (gen !== searchGenRef.current) return;
      setBarcode('');
      addToTried(q);

      if (local.length === 1) {
        setPhase({ kind: 'found-local', part: local[0], query: q });
        return;
      }
      if (local.length > 1) {
        setPhase({ kind: 'multiple', parts: local, query: q });
        return;
      }

      setPhase({ kind: 'searching', step: 'online', query: q });
      const online = await searchPartsOnline(q);
      if (gen !== searchGenRef.current) return;

      if (online.length > 0) {
        setPhase({ kind: 'found-online', result: online[0], query: q });
        return;
      }

      setPhase({ kind: 'not-found', query: q });
    } catch (e) {
      if (gen !== searchGenRef.current) return;
      setBarcode('');
      setError((e as Error).message);
      setPhase({ kind: 'scan' });
    }
  }, [triedCodes]);

  const handleBarcodeKeyDown = (e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'Enter' && barcode.trim()) {
      handleScan(barcode);
    }
  };

  const handleAddToExisting = async (part: Part, printAfter = false) => {
    if (!locationId) { setError('Select a location first'); return; }
    const q = lastQueryRef.current;
    setPhase({ kind: 'adding' });
    try {
      await addStock({
        partId: part.id,
        locationId: locationId as number,
        quantity,
        unitPrice: unitPrice ? parseFloat(unitPrice) : null,
      });
      await refresh();
      pushRecentScan(q, part.partNumber, part.description, quantity);
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
      setPhase({ kind: 'found-local', part, query: lastQueryRef.current });
    }
  };

  const handleCreateAndAdd = async (result: PartSearchResult, query: string, printAfter = false) => {
    if (!locationId) { setError('Select a location first'); return; }
    setPhase({ kind: 'adding' });
    try {
      const res = await quickAddPart({
        partNumber: result.mpn || query,
        description: result.shortDescription,
        manufacturer: result.manufacturer,
        datasheetUrl: result.datasheetUrl,
        locationId: locationId as number,
        quantity,
        unitPrice: unitPrice ? parseFloat(unitPrice) : null,
      });
      await refresh();
      pushRecentScan(query, res.part.partNumber, res.part.description, quantity);
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
      setPhase({ kind: 'found-online', result, query });
    }
  };

  const stockFormProps = {
    error,
    locationId,
    locations,
    quantity,
    unitPrice,
    onLocationChange: setLocationId,
    onQuantityChange: setQuantity,
    onUnitPriceChange: setUnitPrice,
    onSkip: resetToScan,
  };

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

        {/* Scan input — always visible and always focusable */}
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
              autoFocus
              autoComplete="off"
              data-1p-ignore
              data-lpignore="true"
              data-form-type="other"
              spellCheck={false}
              className="flex-1 rounded-md border border-gray-300 px-3 py-2.5 text-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
            />
            <button
              onClick={() => barcode.trim() && handleScan(barcode)}
              disabled={!barcode.trim()}
              className="rounded-md bg-blue-600 px-4 py-2.5 text-sm font-medium text-white hover:bg-blue-700 disabled:cursor-not-allowed disabled:opacity-50"
            >
              Search
            </button>
          </div>
          {success && (
            <p className="mt-2 text-sm font-medium text-green-600">{success}</p>
          )}
          {error && phase.kind === 'scan' && (
            <p className="mt-2 text-sm font-medium text-red-600">{error}</p>
          )}
        </div>

        {/* Phase-specific content */}

        {(phase.kind === 'searching' || phase.kind === 'adding') && (
          <div className="flex items-center gap-3 rounded-xl border border-gray-200 bg-white p-5 shadow-sm">
            <svg className="h-5 w-5 shrink-0 animate-spin text-blue-500" viewBox="0 0 24 24" fill="none">
              <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
              <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8v4a4 4 0 00-4 4H4z" />
            </svg>
            <span className="text-sm text-gray-600">
              {phase.kind === 'adding'
                ? 'Saving…'
                : phase.step === 'local'
                ? <>Searching local database for <span className="font-mono font-medium text-gray-900">{phase.query}</span>…</>
                : <>Searching online for <span className="font-mono font-medium text-gray-900">{phase.query}</span>…</>}
            </span>
          </div>
        )}

        {phase.kind === 'already-tried' && (
          <div className="rounded-xl border border-amber-200 bg-amber-50 p-5 shadow-sm">
            <p className="text-sm font-medium text-amber-800">
              Code <span className="font-mono">{phase.query}</span> already tried
            </p>
            <p className="mt-1 text-sm text-amber-700">
              This code was searched but not added. Scan a different barcode or act on the previous result.
            </p>
            <button
              onClick={() => {
                removeFromTried(phase.query);
                setPhase({ kind: 'scan' });
              }}
              className="mt-3 text-sm font-medium text-amber-800 underline hover:text-amber-900"
            >
              Search again anyway
            </button>
          </div>
        )}

        {phase.kind === 'multiple' && (
          <div className="rounded-xl border border-gray-200 bg-white shadow-sm">
            <div className="border-b border-gray-100 px-5 py-3">
              <p className="text-sm font-medium text-gray-700">
                {phase.parts.length} existing local parts found for code{' '}
                <span className="font-mono text-gray-900">{phase.query}</span> — select one:
              </p>
            </div>
            <ul className="divide-y divide-gray-100">
              {phase.parts.map((p) => (
                <li key={p.id}>
                  <button
                    onClick={() => setPhase({ kind: 'found-local', part: p, query: phase.query })}
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
            <div className="border-t border-gray-100 flex items-center justify-between px-5 py-3">
              <button
                onClick={() => searchOnline(phase.query)}
                className="text-sm font-medium text-blue-600 hover:text-blue-800"
              >
                Look up on the Internet
              </button>
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
                <div>
                  <span className="text-xs font-semibold uppercase tracking-wide text-blue-600">
                    Found in existing inventory
                  </span>
                  <span className="ml-2 text-xs text-gray-500">
                    for code <span className="font-mono text-gray-700">{phase.query}</span>
                  </span>
                </div>
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
                {...stockFormProps}
                onSubmit={() => handleAddToExisting(phase.part)}
                submitLabel="Add Stock"
                onSubmitAndPrint={() => handleAddToExisting(phase.part, true)}
                submitAndPrintLabel="Add & Print Label"
              />
              <div className="border-t border-gray-100 pt-3">
                <button
                  onClick={() => searchOnline(phase.query)}
                  className="text-sm font-medium text-blue-600 hover:text-blue-800"
                >
                  Not the right part? Look up on the Internet
                </button>
              </div>
            </div>
          </div>
        )}

        {phase.kind === 'found-online' && (
          <div className="rounded-xl border border-emerald-200 bg-white shadow-sm">
            <div className="border-b border-gray-100 bg-emerald-50/50 px-5 py-3">
              <div>
                <span className="text-xs font-semibold uppercase tracking-wide text-emerald-600">
                  New part — found online
                </span>
                <span className="ml-2 text-xs text-gray-500">
                  for code <span className="font-mono text-gray-700">{phase.query}</span>
                </span>
              </div>
            </div>
            <div className="space-y-4 p-5">
              <div>
                <p className="text-base font-semibold text-gray-900">
                  {phase.result.mpn || phase.query}
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
                {...stockFormProps}
                onSubmit={() => handleCreateAndAdd(phase.result, phase.query)}
                submitLabel="Create & Add Stock"
                onSubmitAndPrint={() => handleCreateAndAdd(phase.result, phase.query, true)}
                submitAndPrintLabel="Create, Add & Print Label"
              />
            </div>
          </div>
        )}

        {phase.kind === 'not-found' && (
          <div className="rounded-xl border border-gray-200 bg-white p-5 shadow-sm">
            <p className="text-sm font-medium text-gray-700">
              No part found for code{' '}
              <span className="font-mono text-gray-900">{phase.query}</span>
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
