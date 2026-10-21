import { useEffect, useId, useMemo, useState } from 'react';
import type { Footprint } from '../api/types';
import { footprintKey, isPackageName, loadFootprints, lookupFootprint } from '../utils/footprints';

interface Props {
  value: string;
  onChange: (value: string) => void;
  className?: string;
  placeholder?: string;
}

/**
 * A text box that offers the footprint list and says what a typed spelling will be stored as.
 * Free text stays possible — the server adds an unknown footprint to the list — but the hint
 * makes it a visible decision rather than a typo nobody notices.
 */
export default function FootprintInput({ value, onChange, className, placeholder }: Props) {
  const listId = useId();
  const [footprints, setFootprints] = useState<Footprint[]>([]);

  useEffect(() => {
    loadFootprints().then(setFootprints).catch(() => {});
  }, []);

  const index = useMemo(() => {
    const map = new Map<string, string>();
    for (const f of footprints) {
      map.set(footprintKey(f.name), f.name);
      for (const alias of f.aliases) map.set(footprintKey(alias), f.name);
    }
    return map;
  }, [footprints]);

  const typed = value.trim();
  const packageName = typed !== '' && isPackageName(typed);
  const canonical = packageName ? lookupFootprint(index, typed) : undefined;

  return (
    <div>
      <input
        name="footprint"
        list={listId}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        placeholder={placeholder ?? 'e.g. SOIC-8, 0402'}
        autoComplete="off"
        className={className}
      />
      <datalist id={listId}>
        {footprints.map((f) => (
          <option key={f.id} value={f.name}>
            {f.aliases.length > 0 ? f.aliases.join(', ') : undefined}
          </option>
        ))}
      </datalist>
      {typed && !packageName && (
        <p className="mt-1 text-xs text-amber-600">Not a package type — the footprint will be left empty</p>
      )}
      {packageName && footprints.length > 0 && (
        canonical ? (
          canonical !== typed && (
            <p className="mt-1 text-xs text-gray-500">Saved as {canonical}</p>
          )
        ) : (
          <p className="mt-1 text-xs text-amber-600">New footprint — it will be added to the list</p>
        )
      )}
    </div>
  );
}
