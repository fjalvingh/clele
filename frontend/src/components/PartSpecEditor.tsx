import {
  useEffect,
  useMemo,
  useRef,
  useState,
  type Dispatch,
  type ReactNode,
  type SetStateAction,
} from 'react';
import { createPortal } from 'react-dom';
import { useAnchoredPosition } from './useAnchoredPosition';
import { getSpecDefinitions, getSpecGroups } from '../api';
import type { SpecDefinition, SpecGroup } from '../api/types';
import { NumberTextInput } from './NumberInput';
import SpecFieldLabel from './SpecFieldLabel';
import SpecNumberField from './SpecNumberField';

// Split "64 KB" → ["64", "KB"] given units list; falls back to [value, first unit]
function parseMultiUnit(value: string, units: string[]): [string, string] {
  for (const u of units) {
    if (value.endsWith(' ' + u)) return [value.slice(0, -(u.length + 1)), u];
  }
  return [value, units[0] ?? ''];
}

/** Render a single spec input based on its type. */
export function SpecField({
  spec,
  value,
  onChange,
}: {
  spec: SpecDefinition;
  value: string;
  onChange: (val: string) => void;
}) {
  if (spec.dataType === 'BOOLEAN') {
    return (
      <div className="mb-4">
        <label className="flex items-center gap-2 cursor-pointer">
          <input
            type="checkbox"
            checked={value === 'true'}
            onChange={(e) => onChange(e.target.checked ? 'true' : 'false')}
            className="rounded border-gray-300 text-blue-600"
          />
          <span className="text-sm font-medium text-gray-700"><SpecFieldLabel spec={spec} /></span>
        </label>
      </div>
    );
  }

  if (spec.dataType === 'SELECT' && spec.options && spec.options.length > 0) {
    return (
      <div className="mb-4">
        <label className="block text-sm font-medium text-gray-700"><SpecFieldLabel spec={spec} /></label>
        <select
          value={value}
          onChange={(e) => onChange(e.target.value)}
          className="mt-1 block w-full rounded-md border border-gray-300 px-3 py-2 text-sm shadow-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
        >
          <option value="">— Select —</option>
          {spec.options.map((opt) => (
            <option key={opt} value={opt}>{opt}</option>
          ))}
        </select>
      </div>
    );
  }

  if (spec.dataType === 'NUMBER') {
    const units = spec.unit ? spec.unit.split(',').map((s) => s.trim()) : [];
    const isMulti = units.length > 1;
    if (isMulti) {
      const [numPart, unitPart] = parseMultiUnit(value, units);
      return (
        <div className="mb-4">
          <label className="block text-sm font-medium text-gray-700"><SpecFieldLabel spec={spec} /></label>
          <div className="mt-1 flex gap-2">
            <NumberTextInput
              decimal
              allowNegative
              value={numPart}
              onChange={(v) => onChange(v ? v + ' ' + unitPart : '')}
              className="block flex-1 rounded-md border border-gray-300 px-3 py-2 text-sm shadow-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
            />
            <select
              value={unitPart}
              onChange={(e) => onChange(numPart ? numPart + ' ' + e.target.value : '')}
              className="rounded-md border border-gray-300 px-2 py-2 text-sm shadow-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
            >
              {units.map((u) => <option key={u} value={u}>{u}</option>)}
            </select>
          </div>
        </div>
      );
    }
    // Everything that is not multi-unit — a metric-prefix field, a unit family, a plain number —
    // is one editor, which is also what carries the min / nominal / max toggle. A field that
    // declares a unit family is stored in that family's base SI unit, so it has to edit with a
    // mantissa + prefix exactly like one that declares `unit` + metricPrefix: otherwise the value
    // reads "150 ns" on the detail page and is edited as 0.00000015.
    return (
      <SpecNumberField
        spec={spec}
        label={<SpecFieldLabel spec={spec} />}
        value={value}
        onChange={onChange}
        inputClassName="block rounded-md border border-gray-300 px-3 py-2 text-sm shadow-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
        selectClassName="rounded-md border border-gray-300 px-2 py-2 text-sm shadow-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
      />
    );
  }

  // TEXT (default)
  return (
    <div className="mb-4">
      <label className="block text-sm font-medium text-gray-700"><SpecFieldLabel spec={spec} /></label>
      <input
        type="text"
        value={value}
        onChange={(e) => onChange(e.target.value)}
        className="mt-1 block w-full rounded-md border border-gray-300 px-3 py-2 text-sm shadow-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
      />
    </div>
  );
}

/** A spec row: the typed field plus the control that takes the spec off the part again. */
function SpecRow({
  spec,
  value,
  onChange,
  onRemove,
}: {
  spec: SpecDefinition;
  value: string;
  onChange: (val: string) => void;
  onRemove: () => void;
}) {
  return (
    <div className="flex items-start gap-2">
      <div className="min-w-0 flex-1">
        <SpecField spec={spec} value={value} onChange={onChange} />
      </div>
      <button
        type="button"
        onClick={onRemove}
        title={`Remove ${spec.name}`}
        aria-label={`Remove ${spec.name}`}
        className="mt-6 shrink-0 rounded p-1 text-gray-400 hover:bg-red-50 hover:text-red-600"
      >
        <svg viewBox="0 0 24 24" className="h-4 w-4" fill="none" stroke="currentColor"
             strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
          <path d="M18 6 6 18M6 6l12 12" />
        </svg>
      </button>
    </div>
  );
}

/**
 * Type-ahead over every spec field in the organisation, to put one on the part. Searching beats a
 * long select: there are several hundred definitions, and the point of this screen is to show only
 * the handful a part actually has.
 *
 * The result list is a **portal, positioned fixed** against the input. This box sits at the bottom
 * of a dialog whose body is `overflow-y-auto`, and an absolutely positioned list inside that box
 * is clipped by it: typing produced a scrollbar and nothing else. Fixed to the viewport it escapes
 * the scroll container, and flips above the input when there is no room below it.
 */
function AddSpecSearch({
  candidates,
  onPick,
}: {
  candidates: SpecDefinition[];
  onPick: (spec: SpecDefinition) => void;
}) {
  const [query, setQuery] = useState('');
  const [open, setOpen] = useState(false);
  const boxRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLInputElement>(null);
  const matches = useMemo(() => {
    const q = query.trim().toLowerCase();
    if (!q) return [];
    return candidates
      .filter((s) => s.name.toLowerCase().includes(q) || s.jsonName.toLowerCase().includes(q))
      // Prefix matches first — typing "volt" should reach "Voltage" before "Supply Voltage".
      .sort((a, b) => {
        const ai = a.name.toLowerCase().indexOf(q);
        const bi = b.name.toLowerCase().indexOf(q);
        return ai !== bi ? ai - bi : a.name.localeCompare(b.name);
      })
      .slice(0, 10);
  }, [query, candidates]);

  const listOpen = open && query.trim() !== '';
  const listRef = useAnchoredPosition<HTMLDivElement>(inputRef, listOpen, { matchWidth: true });

  // Close on an outside click. The list is in a portal, so it is outside `boxRef` in the DOM and
  // has to be checked separately or picking an entry would close the list before the click landed.
  useEffect(() => {
    const onDown = (e: MouseEvent) => {
      const target = e.target as Node;
      if (boxRef.current?.contains(target) || listRef.current?.contains(target)) return;
      setOpen(false);
    };
    document.addEventListener('mousedown', onDown);
    return () => document.removeEventListener('mousedown', onDown);
  }, [listRef]);

  const pick = (spec: SpecDefinition) => {
    onPick(spec);
    setQuery('');
    setOpen(false);
  };

  return (
    <div ref={boxRef} className="relative">
      <label className="block text-sm font-medium text-gray-700">Add a specification</label>
      <input
        ref={inputRef}
        type="text"
        value={query}
        onChange={(e) => {
          setQuery(e.target.value);
          setOpen(true);
        }}
        onFocus={() => setOpen(true)}
        onKeyDown={(e) => {
          if (e.key === 'Enter' && matches.length > 0) {
            e.preventDefault();
            pick(matches[0]);
          } else if (e.key === 'Escape') {
            setOpen(false);
          }
        }}
        placeholder="Search spec fields by name…"
        className="mt-1 block w-full rounded-md border border-gray-300 px-3 py-2 text-sm shadow-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
      />
      {listOpen && createPortal(
        <div
          ref={listRef}
          style={{
            position: 'fixed',
            // Hidden until the layout effect has measured the input; that runs before paint.
            visibility: 'hidden',
            // Above the dialog, which Modal renders at z-50.
            zIndex: 60,
          }}
          className="overflow-y-auto rounded-md border border-gray-200 bg-surface shadow-lg"
        >
          {matches.length === 0 ? (
            <p className="px-3 py-2 text-sm text-gray-400">
              No spec field matches — add it on the Spec Fields screen first.
            </p>
          ) : (
            matches.map((spec) => (
              <button
                key={spec.id}
                type="button"
                onClick={() => pick(spec)}
                className="block w-full px-3 py-2 text-left text-sm hover:bg-blue-50"
              >
                <span className="font-medium text-gray-900">{spec.name}</span>
                {spec.unit ? <span className="text-gray-500"> ({spec.unit})</span> : null}
                <span className="block text-xs text-gray-500">{spec.groupName}</span>
              </button>
            ))
          )}
        </div>,
        document.body,
      )}
    </div>
  );
}

interface Props {
  /** Spec values by jsonName, as edited. Empty strings are kept — that is a half-typed field. */
  values: Record<string, string>;
  onValuesChange: Dispatch<SetStateAction<Record<string, string>>>;
  /**
   * Keys that stay on the form even while empty: everything the part arrived with, plus anything
   * picked from the search during this edit. Without this a row would vanish under the cursor the
   * moment its last character was deleted — clearing a field is how you retype it, not how you
   * remove it (that is the per-row remove button, which drops the key from here).
   */
  shownKeys: string[];
  onShownKeysChange: Dispatch<SetStateAction<string[]>>;
  /** Shown when nothing is on the form yet. */
  emptyText?: string;
  /** Field columns. Two on a full-width page, one in a dialog (the default). */
  columns?: 1 | 2;
  /** Extra explanation under the "Other" heading, for callers whose source invents keys. */
  otherNote?: ReactNode;
}

/**
 * The specifications block of the part create / edit dialogs: the specs the part actually carries,
 * grouped, each removable, plus a type-ahead to put another one on. Deliberately *not* driven by
 * the category's spec list — that is several hundred fields when no category is picked, and a part
 * may well carry a spec its category does not list.
 */
export default function PartSpecEditor({
  values,
  onValuesChange,
  shownKeys,
  onShownKeysChange,
  emptyText = 'No specifications yet. Search below to add one.',
  columns = 1,
  otherNote,
}: Props) {
  const [specDefs, setSpecDefs] = useState<SpecDefinition[]>([]);
  const [specGroups, setSpecGroups] = useState<SpecGroup[]>([]);

  // Every definition in the organisation, plus the groups for their display order. Not the
  // category-scoped subset: what is shown is driven by the values the part has, and a part can
  // carry a spec its category does not list.
  useEffect(() => {
    getSpecDefinitions().then(setSpecDefs).catch(() => setSpecDefs([]));
    getSpecGroups().then(setSpecGroups).catch(() => setSpecGroups([]));
  }, []);

  // A spec is on the form when it holds a value, or is one of the keys kept on it.
  const isShown = (key: string) =>
    (values[key] !== undefined && values[key] !== '') || shownKeys.includes(key);

  const defsByKey = useMemo(
    () => new Map(specDefs.map((d) => [d.jsonName, d])),
    [specDefs]
  );

  // Shown specs, bucketed into their groups, groups in their configured display order.
  const shownGroups = useMemo(() => {
    const order = new Map(specGroups.map((g, i) => [g.name, i]));
    const buckets = new Map<string, SpecDefinition[]>();
    for (const def of specDefs) {
      if (!isShown(def.jsonName)) continue;
      const name = def.groupName ?? 'Other';
      const bucket = buckets.get(name);
      if (bucket) bucket.push(def);
      else buckets.set(name, [def]);
    }
    return [...buckets.entries()]
      .map(([name, specs]) => ({ name, specs }))
      .sort((a, b) => (order.get(a.name) ?? 999) - (order.get(b.name) ?? 999));
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [specDefs, specGroups, values, shownKeys]);

  // Values the part carries under a key no definition covers.
  const undefinedKeys = useMemo(
    () => Object.keys(values).filter((k) => !defsByKey.has(k) && isShown(k)),
  // eslint-disable-next-line react-hooks/exhaustive-deps
    [values, defsByKey, shownKeys]
  );

  // The search offers what is not already on the form.
  const addableSpecs = useMemo(
    () => specDefs.filter((d) => !isShown(d.jsonName)),
  // eslint-disable-next-line react-hooks/exhaustive-deps
    [specDefs, values, shownKeys]
  );

  const addSpec = (spec: SpecDefinition) => {
    onShownKeysChange((prev) => (prev.includes(spec.jsonName) ? prev : [...prev, spec.jsonName]));
    onValuesChange((prev) => ({ ...prev, [spec.jsonName]: prev[spec.jsonName] ?? '' }));
  };

  const gridClass = columns === 2 ? 'grid grid-cols-1 gap-x-4 sm:grid-cols-2' : '';

  const removeSpec = (key: string) => {
    onShownKeysChange((prev) => prev.filter((k) => k !== key));
    onValuesChange((prev) => {
      const next = { ...prev };
      delete next[key];
      return next;
    });
  };

  return (
    <div className="mt-2">
      <p className="mb-2 text-sm font-medium text-gray-700">Specifications</p>

      {shownGroups.length === 0 && undefinedKeys.length === 0 && (
        <p className="mb-3 text-xs text-gray-400">{emptyText}</p>
      )}

      {shownGroups.map((group) => (
        <div key={group.name} className="mb-4">
          <h4 className="mb-2 border-b border-gray-200 pb-1 text-xs font-semibold uppercase tracking-wider text-blue-700/80">
            {group.name}
          </h4>
          <div className={gridClass}>
          {group.specs.map((spec) => (
            <SpecRow
              key={spec.id}
              spec={spec}
              value={values[spec.jsonName] ?? ''}
              onChange={(val) => onValuesChange((prev) => ({ ...prev, [spec.jsonName]: val }))}
              onRemove={() => removeSpec(spec.jsonName)}
            />
          ))}
          </div>
        </div>
      ))}

      {/* Values whose key no definition covers — editable as plain text so they are not lost. */}
      {undefinedKeys.length > 0 && (
        <div className="mb-4">
          <h4 className="mb-2 border-b border-gray-200 pb-1 text-xs font-semibold uppercase tracking-wider text-gray-500">
            Other
          </h4>
          {otherNote && <p className="mb-3 text-xs text-gray-500 dark:text-gray-400">{otherNote}</p>}
          <div className={gridClass}>
          {undefinedKeys.map((key) => (
            <SpecRow
              key={key}
              spec={{
                id: -1,
                jsonName: key,
                name: key,
                dataType: 'TEXT',
                displayOrder: 0,
                groupId: -1,
                groupName: 'Other',
              }}
              value={values[key] ?? ''}
              onChange={(val) => onValuesChange((prev) => ({ ...prev, [key]: val }))}
              onRemove={() => removeSpec(key)}
            />
          ))}
          </div>
        </div>
      )}

      <AddSpecSearch candidates={addableSpecs} onPick={addSpec} />
    </div>
  );
}
