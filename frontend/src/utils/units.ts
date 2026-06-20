// Metric (SI) prefix scaling for numeric spec values stored in a base SI unit.
// A spec value like 0.009 stored in base unit "A" is rendered as "9 mA"; an
// edit field shows the mantissa (9) next to a prefix dropdown (mA), and converts
// back to the base unit (0.009) on save.

interface Prefix {
  symbol: string;
  exp: number;
}

// Ordered descending by exponent — used both for engineering selection and the
// edit dropdown (largest prefix first).
const PREFIXES: Prefix[] = [
  { symbol: 'T', exp: 12 },
  { symbol: 'G', exp: 9 },
  { symbol: 'M', exp: 6 },
  { symbol: 'k', exp: 3 },
  { symbol: '', exp: 0 },
  { symbol: 'm', exp: -3 },
  { symbol: 'µ', exp: -6 },
  { symbol: 'n', exp: -9 },
  { symbol: 'p', exp: -12 },
];

const MAX_EXP = PREFIXES[0].exp;
const MIN_EXP = PREFIXES[PREFIXES.length - 1].exp;

function expOf(symbol: string): number {
  return PREFIXES.find((p) => p.symbol === symbol)?.exp ?? 0;
}

// Strip binary-float noise (e.g. 9 * 1e-3 -> 0.009, not 0.009000000000000001)
// while keeping up to 12 significant digits.
function clean(n: number): number {
  if (!isFinite(n)) return n;
  return parseFloat(n.toPrecision(12));
}

function toNumber(value: string | number | null | undefined): number | null {
  if (value === '' || value == null) return null;
  const n = typeof value === 'number' ? value : parseFloat(value);
  return isNaN(n) ? null : n;
}

// Pick the engineering prefix so |mantissa| falls in [1, 1000); 0 keeps no prefix.
function pick(base: number): { mantissa: number; symbol: string } {
  if (base === 0 || !isFinite(base)) return { mantissa: base, symbol: '' };
  let exp = Math.floor(Math.log10(Math.abs(base)) / 3) * 3;
  if (exp > MAX_EXP) exp = MAX_EXP;
  if (exp < MIN_EXP) exp = MIN_EXP;
  const symbol = PREFIXES.find((p) => p.exp === exp)?.symbol ?? '';
  return { mantissa: clean(base / Math.pow(10, exp)), symbol };
}

/** Prefix choices for an edit dropdown, with the base unit appended (e.g. "mA"). */
export function prefixOptions(baseUnit: string): { value: string; label: string }[] {
  return PREFIXES.map((p) => ({ value: p.symbol, label: `${p.symbol}${baseUnit}` }));
}

/** "9 mA" from (0.009, "A"). Empty / non-numeric input is returned unchanged. */
export function formatMetric(value: string | number, baseUnit: string): string {
  const n = toNumber(value);
  if (n == null) return String(value ?? '');
  const { mantissa, symbol } = pick(n);
  return `${mantissa} ${symbol}${baseUnit}`;
}

/** Seed an edit field from a stored base value: pick the natural prefix. */
export function splitMetric(value: string | number): { mantissa: string; prefix: string } {
  const n = toNumber(value);
  if (n == null) return { mantissa: '', prefix: '' };
  const { mantissa, symbol } = pick(n);
  return { mantissa: String(mantissa), prefix: symbol };
}

/** Mantissa shown for a stored base value under an explicitly chosen prefix. */
export function mantissaForPrefix(value: string | number, prefix: string): string {
  const n = toNumber(value);
  if (n == null) return '';
  return String(clean(n / Math.pow(10, expOf(prefix))));
}

/** Edit field (mantissa + prefix) back to the stored base-unit value. "" stays "". */
export function toBaseValue(mantissa: string, prefix: string): string {
  const n = toNumber(mantissa);
  if (n == null) return '';
  return String(clean(n * Math.pow(10, expOf(prefix))));
}
