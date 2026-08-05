import { useEffect, useState } from 'react';
import { getAutoCategorizeStatus, startAutoCategorize } from '../api';
import type { CategorizationStatus } from '../api/types';

export default function AdminActionsPage() {
  const [catStatus, setCatStatus] = useState<CategorizationStatus | null>(null);
  const [error, setError] = useState<string | null>(null);

  // Poll the auto-categorization job until it finishes.
  const pollCategorize = () => {
    getAutoCategorizeStatus()
      .then((st) => {
        setCatStatus(st);
        if (st.running) {
          setTimeout(pollCategorize, 1500);
        }
      })
      .catch((e: Error) => setError(e.message));
  };

  // Resume progress display if a categorization job is already running (e.g. after a reload).
  useEffect(() => {
    getAutoCategorizeStatus()
      .then((st) => {
        if (st.running) {
          setCatStatus(st);
          setTimeout(pollCategorize, 1500);
        }
      })
      .catch(() => {});
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const handleAutoCategorize = async (onlyUncategorized: boolean) => {
    const msg = onlyUncategorized
      ? 'Auto-categorize only the uncategorized parts using the local AI?'
      : 'Auto-categorize ALL parts using the local AI? This overwrites existing categories.';
    if (!confirm(msg)) return;
    try {
      const st = await startAutoCategorize(onlyUncategorized);
      setCatStatus(st);
      setTimeout(pollCategorize, 1000);
    } catch (e: unknown) {
      alert((e as Error).message);
    }
  };

  return (
    <div className="p-4 md:p-8">
      <h1 className="mb-6 text-2xl font-bold text-gray-900">Admin Actions</h1>

      <div className="rounded-lg border border-gray-200 bg-white p-6">
        <h2 className="mb-1 text-lg font-semibold text-gray-900">Auto-categorization</h2>
        <p className="mb-4 text-sm text-gray-500">
          Assign categories to parts using the local AI (Ollama).
        </p>
        <div className="flex gap-3">
          <button
            onClick={() => handleAutoCategorize(true)}
            disabled={catStatus?.running}
            className="rounded-lg border border-gray-300 px-4 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50 disabled:opacity-50"
            title="Categorize only the parts that have no category yet (local AI / Ollama)"
          >
            ✨ Categorize uncategorized
          </button>
          <button
            onClick={() => handleAutoCategorize(false)}
            disabled={catStatus?.running}
            className="rounded-lg border border-gray-300 px-4 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50 disabled:opacity-50"
            title="Re-categorize every part, overwriting existing assignments (local AI / Ollama)"
          >
            {catStatus?.running ? 'Categorizing…' : '✨ Re-categorize all'}
          </button>
        </div>

        {error && <p className="mt-4 text-sm text-red-600">{error}</p>}

        {/* Progress / result */}
        {catStatus && (catStatus.running || catStatus.finishedAt) && (
          <div className="mt-4 rounded-lg border border-gray-200 bg-gray-50 p-4">
            <div className="flex items-center justify-between text-sm">
              <span className="font-medium text-gray-700">
                {catStatus.running
                  ? `Auto-categorizing parts… ${catStatus.processed}/${catStatus.total}`
                  : `Auto-categorization complete — assigned ${catStatus.assigned}, skipped ${catStatus.skipped} of ${catStatus.total}`}
              </span>
              {!catStatus.running && (
                <button
                  onClick={() => setCatStatus(null)}
                  className="text-xs text-gray-400 hover:text-gray-600"
                >
                  Dismiss
                </button>
              )}
            </div>
            <div className="mt-2 h-2 w-full overflow-hidden rounded-full bg-gray-200">
              <div
                className="h-full rounded-full bg-blue-600 transition-all"
                style={{ width: `${catStatus.total ? (catStatus.processed / catStatus.total) * 100 : 0}%` }}
              />
            </div>
            {catStatus.lastError && (
              <p className="mt-2 text-xs text-red-600">{catStatus.lastError}</p>
            )}
          </div>
        )}
      </div>
    </div>
  );
}
