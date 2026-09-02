import { useRef, useState, type InputHTMLAttributes } from 'react';

/**
 * A plain text box for entering a number.
 *
 * Never use `<input type="number">` in this app: browsers render it with up/down spinner
 * buttons, and the combination of a numeric state and a spinner input makes the field
 * unusable — a value that cannot be cleared (deleting the last digit parses back to the
 * fallback, which then reappears in front of whatever you type next).
 *
 * This component keeps the raw text the user typed in its own state, so the box can be
 * empty while typing, and reports the parsed value (or `null` when empty) to the parent.
 */

const inputClass =
  'mt-1 block w-full rounded-md border border-gray-300 px-3 py-2 text-sm shadow-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500';

type BaseProps = Omit<InputHTMLAttributes<HTMLInputElement>, 'type' | 'value' | 'onChange'> & {
  value: number | null | undefined;
  onChange: (value: number | null) => void;
  /** Allow a decimal point (prices, tolerances). Off by default: whole counts only. */
  decimal?: boolean;
  /** Allow a leading minus sign. Off by default. */
  allowNegative?: boolean;
};

function sanitize(raw: string, decimal: boolean, allowNegative: boolean): string {
  let out = raw.replace(decimal ? /[^0-9.,-]/g : /[^0-9-]/g, '').replace(/,/g, '.');
  const negative = allowNegative && out.startsWith('-');
  out = out.replace(/-/g, '');
  if (decimal) {
    const [head, ...tail] = out.split('.');
    out = tail.length ? `${head}.${tail.join('')}` : head;
  }
  return negative ? `-${out}` : out;
}

function parse(text: string): number | null {
  if (text.trim() === '' || text === '-' || text === '.' || text === '-.') return null;
  const n = Number(text);
  return Number.isNaN(n) ? null : n;
}

function show(value: number | null | undefined): string {
  return value === null || value === undefined ? '' : String(value);
}

type TextProps = Omit<InputHTMLAttributes<HTMLInputElement>, 'type' | 'value' | 'onChange'> & {
  value: string;
  onChange: (value: string) => void;
  decimal?: boolean;
  allowNegative?: boolean;
};

/**
 * The same box for forms that keep the raw text in their own state (and parse it themselves on
 * submit). What comes back is already stripped of anything that is not part of a number.
 */
export function NumberTextInput({
  value,
  onChange,
  decimal = false,
  allowNegative = false,
  className,
  ...rest
}: TextProps) {
  return (
    <input
      type="text"
      inputMode={decimal ? 'decimal' : 'numeric'}
      value={value}
      onChange={(e) => onChange(sanitize(e.target.value, decimal, allowNegative))}
      className={className ?? inputClass}
      {...rest}
    />
  );
}

export default function NumberInput({
  value,
  onChange,
  decimal = false,
  allowNegative = false,
  className,
  ...rest
}: BaseProps) {
  const [text, setText] = useState(() => show(value));
  const seen = useRef<number | null>(value ?? null);

  // Follow the parent when it changes the value behind our back (form reset, modal reopened),
  // but leave the text alone while it still means the same number — otherwise "1." or a
  // half-typed "1.50" gets rewritten under the cursor. Adjusting state during render is the
  // documented way to react to a changed prop: https://react.dev/reference/react/useState
  if (seen.current !== (value ?? null)) {
    seen.current = value ?? null;
    if (parse(text) !== (value ?? null)) setText(show(value));
  }

  return (
    <NumberTextInput
      value={text}
      onChange={(next) => {
        setText(next);
        onChange(parse(next));
      }}
      decimal={decimal}
      allowNegative={allowNegative}
      className={className}
      {...rest}
    />
  );
}

type FieldProps = BaseProps & { label: string; error?: string };

/** `NumberInput` with the label / error markup of `FormField`. */
export function NumberField({ label, error, ...rest }: FieldProps) {
  return (
    <div className="mb-4">
      <label className="block text-sm font-medium text-gray-700">{label}</label>
      <NumberInput {...rest} />
      {error && <p className="mt-1 text-xs text-red-600">{error}</p>}
    </div>
  );
}
