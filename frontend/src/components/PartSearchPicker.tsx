import { useEffect, useState } from 'react';
import { getParts } from '../api';
import { type Part } from '../api/types';

interface PartSearchPickerProps {
  /** Called with the part the user clicked. */
  onSelect: (part: Part) => void;
  placeholder?: string;
  autoFocus?: boolean;
  /** Text to start the search with — e.g. the MPN of an unmatched row of a supplier file. */
  initialQuery?: string;
}

/**
 * A search box over the catalogue with a result list underneath: type, click a part. The same
 * search the Parts screen runs, debounced so a fast typist does not fire a query per keystroke.
 */
export default function PartSearchPicker({ onSelect, placeholder, autoFocus, initialQuery }: PartSearchPickerProps) {
  const [query, setQuery] = useState(initialQuery ?? '');
  const [results, setResults] = useState<Part[]>([]);
  const [loading, setLoading] = useState(false);
  // The part number put in the box by picking a result. Searching for it again would only pop the
  // list back open over the choice just made.
  const [picked, setPicked] = useState<string | null>(null);

  useEffect(() => {
    const term = query.trim();
    const t = setTimeout(() => {
      if (!term || query === picked) { setResults([]); return; }
      setLoading(true);
      getParts(term)
        .then(setResults)
        .catch(() => setResults([]))
        .finally(() => setLoading(false));
    }, 300);
    return () => clearTimeout(t);
  }, [query, picked]);

  return (
    <div>
      <input
        className="w-full rounded-lg border border-gray-300 px-3 py-2 text-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
        placeholder={placeholder ?? 'Type part number or name…'}
        value={query}
        onChange={(e) => setQuery(e.target.value)}
        autoFocus={autoFocus}
      />
      {loading && <p className="text-xs text-gray-400 mt-1">Searching…</p>}
      {results.length > 0 && (
        <div className="mt-1 max-h-64 overflow-auto rounded-lg border border-gray-200 bg-surface shadow-sm">
          <table className="min-w-full divide-y divide-gray-100">
            <thead className="bg-gray-50 sticky top-0">
              <tr>
                <th className="px-3 py-2 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">Part #</th>
                <th className="px-3 py-2 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">Description</th>
                <th className="px-3 py-2 text-right text-xs font-medium text-gray-500 uppercase tracking-wider">On hand</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-50">
              {results.map((p) => (
                <tr
                  key={p.id}
                  onClick={() => { onSelect(p); setPicked(p.partNumber); setQuery(p.partNumber); setResults([]); }}
                  className="cursor-pointer hover:bg-blue-500/10"
                >
                  <td className="px-3 py-2 text-sm font-medium text-gray-900 whitespace-nowrap">{p.partNumber}</td>
                  <td className="px-3 py-2 text-sm text-gray-500 max-w-xs truncate">{p.description ?? '—'}</td>
                  <td className="px-3 py-2 text-sm text-right whitespace-nowrap">
                    <span className={(p.totalQuantity ?? 0) > 0 ? 'text-green-700 font-medium' : 'text-gray-400'}>
                      {p.totalQuantity ?? 0}
                    </span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
