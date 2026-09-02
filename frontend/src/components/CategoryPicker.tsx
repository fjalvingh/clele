import { useEffect, useMemo, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import type { CategoryTree } from '../api/types';
import { useAnchoredPosition } from './useAnchoredPosition';

interface FlatCategory {
  id: number;
  /** Leaf name only — what the user actually recognizes. */
  name: string;
  /** Ancestor path without the leaf ("Semiconductors > ICs"), empty for a root category. */
  parentPath: string;
  /** Full path including the leaf — used for matching and tooltips. */
  breadcrumb: string;
}

// Flattens the tree into a searchable list, carrying each node's full breadcrumb so typing
// any ancestor's name (not just the leaf) narrows the match. `excludeId` drops that node and
// its whole subtree — used by the parent-category picker to prevent a category becoming its
// own ancestor.
function flatten(nodes: CategoryTree[], excludeId: number | null | undefined, trail: string[] = []): FlatCategory[] {
  const out: FlatCategory[] = [];
  for (const node of nodes) {
    if (excludeId != null && node.id === excludeId) continue;
    const parentPath = trail.join(' > ');
    const breadcrumb = [...trail, node.name].join(' > ');
    out.push({ id: node.id, name: node.name, parentPath, breadcrumb });
    out.push(...flatten(node.children, excludeId, [...trail, node.name]));
  }
  return out;
}

const MAX_SHOWN = 50;

/** How wide the list may grow past its input, in px — `max-w-[26rem]` as a number. */
const MAX_LIST_WIDTH = 416;

interface CategoryPickerProps {
  label?: string;
  categories: CategoryTree[];
  value: number | null;
  onChange: (id: number | null) => void;
  emptyLabel?: string;
  excludeId?: number | null;
  className?: string;
}

// Type-ahead replacement for the flat/indented <select> category pickers: typing any word from
// the breadcrumb (e.g. "logic" or "ics") narrows a tree that can otherwise run to hundreds of
// leaves. Selecting an option, clearing, and keyboard nav (Up/Down/Enter/Escape) all commit
// through the same onChange as the old <select> did.
export default function CategoryPicker({
  label,
  categories,
  value,
  onChange,
  emptyLabel = '— Uncategorized —',
  excludeId,
  className,
}: CategoryPickerProps) {
  const flat = useMemo(() => flatten(categories, excludeId), [categories, excludeId]);
  const selected = value != null ? flat.find((c) => c.id === value) : undefined;

  const [query, setQuery] = useState('');
  const [open, setOpen] = useState(false);
  const [highlight, setHighlight] = useState(0);
  const inputRef = useRef<HTMLInputElement>(null);
  // The list is a portal: inside a dialog body (`overflow-y-auto`) an absolute list is clipped by
  // it, which showed as a scrollbar and no list at all.
  const listRef = useAnchoredPosition<HTMLUListElement>(inputRef, open, { maxWidth: MAX_LIST_WIDTH });

  // Keep the input's text in sync with the current selection while the user isn't actively typing.
  // Show the leaf name, not the full breadcrumb: the input is often narrow (48 in the Parts filter
  // bar) and a long path truncates to its *front*, hiding the only part that identifies the choice.
  // The full path stays available as the title tooltip and under the dropdown entry.
  useEffect(() => {
    if (!open) setQuery(selected ? selected.name : '');
  }, [selected, open]);

  const words = query.trim().toLowerCase().split(/\s+/).filter(Boolean);
  const matches =
    words.length === 0
      ? flat
      : flat.filter((c) => {
          const hay = c.breadcrumb.toLowerCase();
          return words.every((w) => hay.includes(w));
        });
  const shown = matches.slice(0, MAX_SHOWN);

  const commit = (id: number | null) => {
    onChange(id);
    setOpen(false);
    setHighlight(0);
    inputRef.current?.blur();
  };

  return (
    <div className={className ?? 'mb-4'}>
      {label && <label className="block text-sm font-medium text-gray-700">{label}</label>}
      <div className="relative mt-1">
        <input
          ref={inputRef}
          type="text"
          value={query}
          onChange={(e) => {
            setQuery(e.target.value);
            setOpen(true);
            setHighlight(0);
          }}
          onFocus={(e) => {
            setOpen(true);
            setHighlight(0);
            e.target.select();
          }}
          onBlur={() => {
            // Deferred so a click on an option (which fires onMouseDown first) isn't lost to the blur.
            setTimeout(() => setOpen(false), 150);
          }}
          onKeyDown={(e) => {
            if (e.key === 'ArrowDown') {
              e.preventDefault();
              setHighlight((h) => Math.min(h + 1, shown.length));
            } else if (e.key === 'ArrowUp') {
              e.preventDefault();
              setHighlight((h) => Math.max(h - 1, 0));
            } else if (e.key === 'Enter') {
              e.preventDefault();
              if (highlight === 0) commit(null);
              else {
                const opt = shown[highlight - 1];
                if (opt) commit(opt.id);
              }
            } else if (e.key === 'Escape') {
              setOpen(false);
              setQuery(selected ? selected.name : '');
              inputRef.current?.blur();
            }
          }}
          placeholder={emptyLabel}
          title={selected?.breadcrumb}
          autoComplete="off"
          className="block w-full rounded-md border border-gray-300 px-3 py-2 text-sm shadow-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
        />
        {value != null && !open && (
          <button
            type="button"
            tabIndex={-1}
            onMouseDown={(e) => {
              e.preventDefault();
              commit(null);
            }}
            aria-label="Clear category"
            className="absolute right-2 top-1/2 -translate-y-1/2 text-gray-400 hover:text-gray-600"
          >
            ×
          </button>
        )}
        {open && createPortal(
          // min-w + w-max: the input can be narrow (w-48 in the Parts filter bar) while category
          // paths are long, so the list is allowed to outgrow it up to a cap.
          <ul
            ref={listRef}
            style={{
              position: 'fixed',
              // Hidden until the layout effect has measured the input; that runs before paint.
              visibility: 'hidden',
              maxWidth: MAX_LIST_WIDTH,
              // Above the dialog, which Modal renders at z-50.
              zIndex: 60,
            }}
            className="w-max overflow-y-auto rounded-md border border-gray-200 bg-surface shadow-lg"
          >
            <li>
              <button
                type="button"
                onMouseDown={(e) => e.preventDefault()}
                onClick={() => commit(null)}
                className={`block w-full px-3 py-1.5 text-left text-sm hover:bg-gray-50 ${highlight === 0 ? 'bg-gray-50' : ''}`}
              >
                {emptyLabel}
              </button>
            </li>
            {shown.length === 0 && (
              <li className="px-3 py-1.5 text-sm text-gray-400">No matching categories</li>
            )}
            {shown.map((opt, i) => (
              <li key={opt.id}>
                <button
                  type="button"
                  onMouseDown={(e) => e.preventDefault()}
                  onClick={() => commit(opt.id)}
                  title={opt.breadcrumb}
                  className={`block w-full px-3 py-1.5 text-left hover:bg-gray-50 ${highlight === i + 1 ? 'bg-gray-50' : ''}`}
                >
                  <span className="block truncate text-sm text-gray-900">{opt.name}</span>
                  {opt.parentPath && (
                    <span className="block truncate text-xs text-gray-400">{opt.parentPath}</span>
                  )}
                </button>
              </li>
            ))}
            {matches.length > shown.length && (
              <li className="px-3 py-1 text-xs text-gray-400">
                +{matches.length - shown.length} more — keep typing to narrow
              </li>
            )}
          </ul>,
          document.body,
        )}
      </div>
    </div>
  );
}
