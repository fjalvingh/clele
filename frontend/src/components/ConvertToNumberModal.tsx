import { useEffect, useState } from 'react';
import { convertSpecToNumber } from '../api';
import type { ConvertToNumberResult, SpecDefinition } from '../api/types';
import FormField from './FormField';
import Modal from './Modal';

// Convert a TEXT spec definition to NUMBER: name the base unit, scan all part values
// (parsing "9 mA" → 0.009 in base "A"), fix any unparseable values, then commit.
export default function ConvertToNumberModal({
  spec,
  onClose,
  onConverted,
}: {
  spec: SpecDefinition;
  onClose: () => void;
  onConverted: () => void;
}) {
  const [unit, setUnit] = useState('');
  const [metricPrefix, setMetricPrefix] = useState(true);
  const [overrides, setOverrides] = useState<Record<string, string>>({});
  const [result, setResult] = useState<ConvertToNumberResult | null>(null);
  // Whether `result` reflects a real scan with a base unit (vs. the initial unit-suggestion scan).
  const [scanned, setScanned] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const runScan = async (u: string, commit: boolean) => {
    setBusy(true);
    setError(null);
    try {
      const r = await convertSpecToNumber(spec.id, { unit: u, metricPrefix, overrides, commit });
      if (commit) {
        onConverted();
        onClose();
        return;
      }
      setResult(r);
      setScanned(u.trim() !== '');
    } catch (e: unknown) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  // On open, scan with no unit just to suggest one; auto-scan with it if found.
  useEffect(() => {
    setBusy(true);
    convertSpecToNumber(spec.id, { unit: '', metricPrefix: true, overrides: {}, commit: false })
      .then((r) => {
        setResult(r);
        if (r.suggestedUnit) {
          setUnit(r.suggestedUnit);
          return runScan(r.suggestedUnit, false);
        }
      })
      .catch((e: unknown) => setError((e as Error).message))
      .finally(() => setBusy(false));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [spec.id]);

  const failures = scanned ? result?.failures ?? [] : [];
  const canConvert = scanned && !busy && unit.trim() !== '' && failures.length === 0;

  return (
    <Modal open onClose={onClose} title={`Convert "${spec.name}" to a number`}>
      <p className="mb-4 text-sm text-gray-500">
        Each part value is parsed into the base unit ({unit || '…'}); e.g. <code>9 mA</code> →{' '}
        <code>0.009</code>. Fix any values that can't be parsed, then convert.
      </p>

      <div className="flex items-end gap-3">
        <div className="flex-1">
          <FormField
            label="Principal (base) unit"
            value={unit}
            onChange={(e) => {
              setUnit(e.target.value);
              setScanned(false); // unit changed → previous scan is stale
            }}
            placeholder="e.g. A, V, F, Hz, m"
          />
        </div>
        <button
          onClick={() => runScan(unit, false)}
          disabled={busy || unit.trim() === ''}
          className="mb-4 rounded-lg border border-gray-300 px-4 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50 disabled:opacity-50"
        >
          {busy ? 'Scanning…' : 'Scan'}
        </button>
      </div>

      <label className="mb-4 flex items-center gap-2 cursor-pointer">
        <input
          type="checkbox"
          checked={metricPrefix}
          onChange={(e) => setMetricPrefix(e.target.checked)}
          className="rounded border-gray-300 text-blue-600"
        />
        <span className="text-sm font-medium text-gray-700">Scale with metric prefixes</span>
      </label>

      {!scanned && result && (
        <div className="mb-4 rounded-lg bg-gray-50 p-3 text-sm text-gray-700">
          {result.total} value{result.total === 1 ? '' : 's'} to convert — enter the base unit and
          click Scan.
        </div>
      )}

      {scanned && result && (
        <div className="mb-4 rounded-lg bg-gray-50 p-3 text-sm text-gray-700">
          {result.converted} of {result.total} value{result.total === 1 ? '' : 's'} parse
          {failures.length > 0 && (
            <span className="text-amber-700">
              {' '}
              — {failures.length} distinct value{failures.length === 1 ? '' : 's'} need fixing
            </span>
          )}
        </div>
      )}

      {failures.length > 0 && (
        <div className="mb-4 max-h-64 space-y-2 overflow-y-auto">
          {failures.map((f) => (
            <div key={f.value} className="flex items-center gap-2">
              <input
                type="text"
                value={overrides[f.value] ?? f.value}
                onChange={(e) =>
                  setOverrides((prev) => ({ ...prev, [f.value]: e.target.value }))
                }
                className="flex-1 rounded-md border border-gray-300 px-3 py-1.5 text-sm shadow-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
              />
              <span className="w-12 shrink-0 text-right text-xs text-gray-400">×{f.count}</span>
            </div>
          ))}
          <button
            onClick={() => runScan(unit, false)}
            disabled={busy}
            className="mt-1 rounded-lg border border-gray-300 px-3 py-1.5 text-xs font-medium text-gray-700 hover:bg-gray-50 disabled:opacity-50"
          >
            Rescan
          </button>
        </div>
      )}

      {error && <p className="mb-3 text-sm text-red-600">{error}</p>}

      <div className="flex justify-end gap-3">
        <button
          onClick={onClose}
          className="rounded-lg border border-gray-300 px-4 py-2 text-sm hover:bg-gray-50"
        >
          Cancel
        </button>
        <button
          onClick={() => runScan(unit, true)}
          disabled={!canConvert}
          className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-50"
        >
          Convert
        </button>
      </div>
    </Modal>
  );
}
