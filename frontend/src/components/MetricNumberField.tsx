import { useState } from 'react';
import { mantissaForPrefix, prefixOptions, splitMetric, toBaseValue } from '../utils/units';

// Edit a numeric spec value that is stored in a base SI unit, shown as a mantissa
// input + a metric-prefix dropdown (e.g. "9" + "mA" -> stored 0.009 in base unit "A").
// The chosen prefix is local UI state so it doesn't jump around while typing; the
// mantissa is derived from the stored base value under that prefix.
export default function MetricNumberField({
  label,
  unit,
  value,
  onChange,
  wrapperClassName = 'mb-4',
  labelClassName = 'block text-sm font-medium text-gray-700',
  inputClassName,
  selectClassName,
}: {
  label: string;
  unit: string;
  value: string;
  onChange: (val: string) => void;
  wrapperClassName?: string;
  labelClassName?: string;
  inputClassName: string;
  selectClassName: string;
}) {
  const [prefix, setPrefix] = useState(() => splitMetric(value).prefix);
  const mantissa = mantissaForPrefix(value, prefix);

  return (
    <div className={wrapperClassName}>
      <label className={labelClassName}>{label}</label>
      <div className="mt-1 flex gap-2">
        <input
          type="number"
          step="any"
          value={mantissa}
          onChange={(e) => onChange(toBaseValue(e.target.value, prefix))}
          className={inputClassName}
        />
        <select
          value={prefix}
          onChange={(e) => {
            setPrefix(e.target.value);
            onChange(toBaseValue(mantissa, e.target.value));
          }}
          className={selectClassName}
        >
          {prefixOptions(unit).map((o) => (
            <option key={o.value} value={o.value}>{o.label}</option>
          ))}
        </select>
      </div>
    </div>
  );
}
