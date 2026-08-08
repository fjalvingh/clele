import { useEffect, useState } from 'react';
import { searchPartImages } from '../api';
import type { ImageSuggestion } from '../api/types';
import Modal from './Modal';

/**
 * Search the web for photos of a part and attach the chosen ones.
 *
 * <p>Extracted from Part Detail so the part kit editor can offer the same thing — a kit's parts all
 * look alike, so picking their photo once on the template is the whole point of it.
 *
 * <p>The caller says only what to <em>do</em> with a picked image (`onAttach`), because the two
 * screens store it in different places: a part's own photo, or a template's, which every generated
 * part will then link to.
 */

// Proxy external images through our backend to avoid CORS / Cloudflare bot-protection issues.
export function proxiedImageUrl(img: { url: string; thumbnailUrl?: string }) {
  const src = img.thumbnailUrl ?? img.url;
  return `${import.meta.env.BASE_URL}api/image-proxy?url=${encodeURIComponent(src)}`;
}

export default function FindImageModal({
  open,
  initialQuery,
  onClose,
  onAttach,
  onAttached,
}: {
  open: boolean;
  /** What to search for when the modal opens — the part number, or the kit's name. */
  initialQuery: string;
  onClose: () => void;
  /** Store one picked image. Called once per selected photo; throwing reports it as failed. */
  onAttach: (file: File) => Promise<void>;
  /** Called once after a run in which at least one image was stored, to refresh the caller's list. */
  onAttached: () => Promise<void> | void;
}) {
  const [query, setQuery] = useState(initialQuery);
  const [suggestions, setSuggestions] = useState<ImageSuggestion[]>([]);
  const [loading, setLoading] = useState(false);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [failed, setFailed] = useState<Set<string>>(new Set());
  const [attaching, setAttaching] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const runSearch = (q: string) => {
    if (!q.trim()) return;
    setLoading(true);
    setSuggestions([]);
    setSelected(new Set());
    setFailed(new Set());
    searchPartImages(q.trim())
      .then(setSuggestions)
      .catch(() => setSuggestions([]))
      .finally(() => setLoading(false));
  };

  // Opening searches straight away for the term the caller suggested — that is right far more often
  // than not, and a wrong guess costs one edit of the box.
  useEffect(() => {
    if (!open) return;
    setQuery(initialQuery);
    setError(null);
    runSearch(initialQuery);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, initialQuery]);

  const toggle = (url: string) =>
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(url)) next.delete(url);
      else next.add(url);
      return next;
    });

  const handleAttach = async () => {
    if (selected.size === 0) return;
    setAttaching(true);
    setError(null);
    // Fetch each image through the same-origin proxy and hand the caller a File: uploading the
    // bytes ourselves sidesteps CORS and tainted-canvas problems, and is what Quick Add does too.
    const errors: string[] = [];
    let i = 0;
    for (const originalUrl of selected) {
      try {
        const suggestion = suggestions.find((s) => s.url === originalUrl);
        const proxyUrl = suggestion
          ? proxiedImageUrl(suggestion)
          : `${import.meta.env.BASE_URL}api/image-proxy?url=${encodeURIComponent(originalUrl)}`;
        const resp = await fetch(proxyUrl);
        if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
        const blob = await resp.blob();
        await onAttach(new File([blob], `image-${i}.png`, { type: blob.type || 'image/png' }));
      } catch (err: unknown) {
        errors.push((err as Error).message);
      }
      i++;
    }
    setAttaching(false);
    await onAttached();

    if (errors.length > 0) {
      const succeeded = selected.size - errors.length;
      setError(
        `${errors.length} photo(s) failed to attach` +
          (succeeded > 0 ? ` (${succeeded} succeeded)` : '') +
          `: ${errors[0]}` +
          (errors.length > 1 ? ` (and ${errors.length - 1} more)` : ''),
      );
      return;
    }
    onClose();
  };

  const visible = suggestions.filter((img) => !failed.has(img.url));

  return (
    <Modal open={open} onClose={onClose} title="Find image">
      <div className="mb-4 flex gap-2">
        <input
          type="text"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          onKeyDown={(e) => e.key === 'Enter' && runSearch(query)}
          placeholder="e.g. LM317 voltage regulator"
          className="flex-1 rounded-lg border border-gray-300 px-3 py-2 text-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
        />
        <button
          onClick={() => runSearch(query)}
          disabled={!query.trim() || loading}
          className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-50"
        >
          Search
        </button>
      </div>

      <div className="min-h-[8rem]">
        {loading ? (
          <p className="text-sm text-gray-400">Searching for photos…</p>
        ) : visible.length === 0 ? (
          <p className="text-sm text-gray-400">No photos found. Try a different search term.</p>
        ) : (
          <div className="grid grid-cols-3 gap-3">
            {visible.map((img) => {
              const isSelected = selected.has(img.url);
              return (
                <button
                  key={img.url}
                  type="button"
                  onClick={() => toggle(img.url)}
                  className={`relative overflow-hidden rounded-lg border-2 transition-all ${
                    isSelected
                      ? 'border-blue-500 ring-2 ring-blue-200'
                      : 'border-gray-200 hover:border-gray-400'
                  }`}
                >
                  <img
                    src={proxiedImageUrl(img)}
                    alt={img.description ?? ''}
                    className="h-24 w-full object-cover"
                    onError={() => setFailed((prev) => new Set(prev).add(img.url))}
                  />
                  {isSelected && (
                    <div className="absolute inset-0 flex items-center justify-center bg-blue-500/20">
                      <span className="rounded-full bg-blue-600 px-2 py-0.5 text-xs font-bold text-white">
                        ✓
                      </span>
                    </div>
                  )}
                </button>
              );
            })}
          </div>
        )}
      </div>

      {error && <p className="mt-3 text-sm text-red-600">{error}</p>}

      <div className="mt-4 flex items-center justify-between">
        <span className="text-xs text-blue-600">
          {selected.size > 0 ? `${selected.size} selected` : ''}
        </span>
        <div className="flex gap-3">
          <button
            onClick={onClose}
            className="rounded-lg border border-gray-300 px-4 py-2 text-sm hover:bg-gray-50"
          >
            Cancel
          </button>
          <button
            onClick={handleAttach}
            disabled={attaching || selected.size === 0}
            className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-50"
          >
            {attaching ? 'Attaching…' : 'Attach selected'}
          </button>
        </div>
      </div>
    </Modal>
  );
}
