import { useState } from 'react';
import type { ReactNode } from 'react';
import type { SpecDefinition } from '../api/types';
import { joinSpecNumber, splitSpecNumber, type SpecNumberParts } from '../utils/specs';
import { mantissaForPrefix, prefixOptions, splitMetric, toBaseValue, unitFamily } from '../utils/units';
import { NumberTextInput } from './NumberInput';
import { SpecTypeIcon } from './SpecFieldLabel';

/**
 * The editor for one numeric spec value: a single box by default, three (min / nominal / max) once
 * the value has a band around it.
 *
 * A datasheet states most parameters as min/typ/max, and the typed rows have always been able to
 * hold a range — but nothing in the UI could enter one, so every range in the catalogue arrived
 * from an import. The toggle beside the label is what opens the other two boxes; a value that
 * already carries a bound opens them by itself, because there is no way to show it otherwise.
 *
 * Values are held in the base SI unit throughout (`utils/specs` splits and rejoins the wire form),
 * so the prefix dropdown is display only — and there is one of it for all three boxes, since a
 * band whose bounds sit in different decades is not a band anyone writes.
 */
export default function SpecNumberField({
  spec,
  label,
  value,
  onChange,
  wrapperClassName = 'mb-4',
  labelClassName = 'block text-sm font-medium text-gray-700',
  inputClassName,
  selectClassName,
}: {
  spec: SpecDefinition;
  label: ReactNode;
  value: string;
  onChange: (val: string) => void;
  wrapperClassName?: string;
  labelClassName?: string;
  inputClassName: string;
  selectClassName: string;
}) {
  const parts = splitSpecNumber(value);
  const banded = parts.min !== '' || parts.max !== '';
  // Only ever asked when the value has no bounds of its own: a banded value is not collapsible
  // without throwing the bounds away, which the toggle does explicitly rather than by remembering.
  const [expanded, setExpanded] = useState(false);
  const showRange = banded || expanded;

  // Which unit the prefix dropdown scales, or null for a field that takes a plain number: a
  // declared unit + metricPrefix, or a scalable unit family. A scale-free family (°C, %, counts)
  // and a multi-unit field both fall through to plain boxes — the latter never reaches here at all,
  // since its value carries its unit as text.
  const units = spec.unit ? spec.unit.split(',').map((s) => s.trim()).filter(Boolean) : [];
  const family = unitFamily(spec.unitFamily);
  const scalable = family && !(family.minExp === 0 && family.maxExp === 0) ? family : null;
  const prefixUnit =
    spec.metricPrefix && units.length === 1 ? units[0]
      : units.length === 0 && scalable ? scalable.baseUnit
        : null;

  const [prefix, setPrefix] = useState(
    () => splitMetric(parts.nominal || parts.min || parts.max).prefix,
  );

  const shown = (stored: string) => (prefixUnit ? mantissaForPrefix(stored, prefix) : stored);

  const setPart = (key: keyof SpecNumberParts, typed: string) => {
    const stored = prefixUnit && typed !== '' ? toBaseValue(typed, prefix) : typed;
    onChange(joinSpecNumber({ ...parts, [key]: stored }));
  };

  // Picking a prefix keeps the typed mantissas and rescales what is stored — typing 9 and then
  // choosing "mA" means 9 mA, which is what the single-value field has always done.
  const changePrefix = (next: string) => {
    const rescale = (stored: string) =>
      stored === '' ? '' : toBaseValue(mantissaForPrefix(stored, prefix), next);
    setPrefix(next);
    onChange(joinSpecNumber({
      min: rescale(parts.min),
      nominal: rescale(parts.nominal),
      max: rescale(parts.max),
    }));
  };

  const toggle = () => {
    if (showRange) {
      // Collapsing drops the bounds: one box has nowhere to keep them, and leaving them stored but
      // invisible would be worse than losing them in front of the user who asked for it.
      onChange(joinSpecNumber({ min: '', nominal: parts.nominal, max: '' }));
      setExpanded(false);
    } else {
      setExpanded(true);
    }
  };

  const boxes: { key: keyof SpecNumberParts; hint: string }[] = showRange
    ? [{ key: 'min', hint: 'Minimum' }, { key: 'nominal', hint: 'Nominal' }, { key: 'max', hint: 'Maximum' }]
    : [{ key: 'nominal', hint: 'Value' }];

  return (
    <div className={wrapperClassName}>
      {/* The toggle sits with the label, beside the type icon — not out at the field's right edge,
          where the row's own remove button already lives. */}
      <div className="flex items-center gap-1">
        <label className={labelClassName}>{label}</label>
        <button
          type="button"
          onClick={toggle}
          aria-pressed={showRange}
          title={showRange
            ? 'Back to a single value — the minimum and maximum are dropped'
            : 'Give this value a minimum and maximum'}
          aria-label={showRange ? 'Back to a single value' : 'Add a minimum and maximum'}
          className={`shrink-0 rounded p-1 transition-colors ${
            showRange
              ? 'bg-blue-50 text-blue-600'
              : 'text-gray-400 hover:bg-gray-100 hover:text-gray-600'
          }`}
        >
          <SpecTypeIcon dataType="RANGE" className="h-4 w-4" />
        </button>
      </div>
      <div className="mt-1 flex gap-2">
        {boxes.map((box) => (
          <div key={box.key} className="min-w-0 flex-1">
            <NumberTextInput
              decimal
              allowNegative
              value={shown(parts[box.key])}
              onChange={(v) => setPart(box.key, v)}
              placeholder={showRange ? box.hint.toLowerCase() : undefined}
              title={box.hint}
              aria-label={`${box.hint}${prefixUnit ? ` (${prefix}${prefixUnit})` : ''}`}
              className={`${inputClassName} w-full`}
            />
          </div>
        ))}
        {prefixUnit && (
          <select
            value={prefix}
            onChange={(e) => changePrefix(e.target.value)}
            className={`${selectClassName} shrink-0`}
            aria-label="Unit"
          >
            {prefixOptions(prefixUnit).map((o) => (
              <option key={o.value} value={o.value}>{o.label}</option>
            ))}
          </select>
        )}
      </div>
    </div>
  );
}
