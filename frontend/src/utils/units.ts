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

// ---------------------------------------------------------------------------
// Unit families — RKM code and the prefix window.
//
// MIRRORS backend UnitFamily.java + MetricUnitParser.java + MetricUnitFormatter.java.
// Keep the three in step; the backend MetricUnitParserTest pins the same example table.
//
// RKM code (IEC 60062) puts the multiplier letter where the decimal point goes: 1K2 is 1.2 kΩ,
// 4R7 is 4.7 Ω, 2n2 is 2.2 nF. The decimal point is the least reliable character in electronics —
// it vanishes on a silkscreen, a photocopy and a badly kerned datasheet table — so components are
// marked, and people type, this way.
// ---------------------------------------------------------------------------

export interface UnitFamilySpec {
  baseUnit: string;
  /** Bare-letter prefix window, as powers of ten. Scale-free families are pinned at [0, 0]. */
  minExp: number;
  maxExp: number;
  /** Render in RKM ("4k7") rather than as "4.7 kΩ". */
  rkm: boolean;
  /** The letter at the base-unit position: "R" for resistance, the unit symbol otherwise. */
  baseMarker: string;
}

const scalable = (baseUnit: string): UnitFamilySpec =>
  ({ baseUnit, minExp: -12, maxExp: 12, rkm: false, baseMarker: baseUnit });
const scaleFree = (baseUnit: string): UnitFamilySpec =>
  ({ baseUnit, minExp: 0, maxExp: 0, rkm: false, baseMarker: baseUnit });

export const UNIT_FAMILIES: Record<string, UnitFamilySpec> = {
  voltage: scalable('V'),
  current: scalable('A'),
  // The three RKM families. Resistance refuses m/µ/n/p and capacitance/inductance refuse k/M/G/T
  // as *bare letters*: 4m7 and 4M7 differ by nine orders of magnitude and one shift key, with no
  // unit symbol present to make the mistake visible. A genuine milliohm is written "0.0047R".
  resistance: { baseUnit: 'Ω', minExp: 0, maxExp: 12, rkm: true, baseMarker: 'R' },
  capacitance: { baseUnit: 'F', minExp: -12, maxExp: 0, rkm: true, baseMarker: 'F' },
  inductance: { baseUnit: 'H', minExp: -12, maxExp: 0, rkm: true, baseMarker: 'H' },
  frequency: scalable('Hz'),
  time: scalable('s'),
  power: scalable('W'),
  energy: scalable('J'),
  charge: scalable('C'),
  length: scalable('m'),
  magnetic_flux_density: scalable('T'),
  luminous_intensity: scalable('cd'),
  luminous_flux: scalable('lm'),
  force: scalable('N'),
  pressure: scalable('Pa'),
  count: scaleFree(''),
  percentage: scaleFree('%'),
  ratio: scaleFree(''),
  ppm: scaleFree('ppm'),
  decibel: scaleFree('dB'),
  decibel_milliwatt: scaleFree('dBm'),
  awg: scaleFree('AWG'),
  lsb: scaleFree('LSB'),
  temperature: scaleFree('°C'),
  kelvin: scaleFree('K'),
  angle: scaleFree('°'),
  area_mm2: scaleFree('mm²'),
  thermal_resistance: scaleFree('°C/W'),
};

export function unitFamily(code: string | null | undefined): UnitFamilySpec | null {
  if (!code) return null;
  return UNIT_FAMILIES[code.trim()] ?? null;
}

// Case-sensitive prefix letters, including the tolerant aliases the backend accepts.
// K is both a kilo alias and the usual RKM spelling ("4K7"); u stands in for µ.
const LETTER_EXP: Record<string, number> = {
  T: 12, G: 9, M: 6, k: 3, K: 3, m: -3, 'µ': -6, u: -6, n: -9, p: -12,
};

/**
 * Render a base-unit value the way people write it — "4k7" for resistance, "9 mA" for current.
 * The exact inverse of parseFamilyValue, which is why no rendering is ever stored.
 *
 * A prefix outside the family's window is never produced, because the parser is required to refuse
 * reading it back: draintosourceresistance really holds 0.0087, and an unrestricted renderer would
 * print "8m7". Outside the window the decimal point stays and the marker suffixes: "0.0087R".
 */
export function formatFamilyValue(value: string | number, familyCode: string | null | undefined): string {
  const family = unitFamily(familyCode);
  const n = toNumber(value);
  if (n == null) return String(value ?? '');
  if (!family) return String(clean(n));

  let exp = n === 0 ? 0 : Math.floor(Math.log10(Math.abs(n)) / 3) * 3;
  exp = Math.max(MIN_EXP, Math.min(MAX_EXP, exp));
  const clamped = exp < family.minExp || exp > family.maxExp;
  if (clamped) exp = exp > family.maxExp ? family.maxExp : family.minExp;

  const mantissa = String(clean(n / Math.pow(10, exp)));
  const prefix = PREFIXES.find((p) => p.exp === exp)?.symbol ?? '';

  if (family.rkm) {
    const letter = exp === 0 ? family.baseMarker : prefix;
    const dot = mantissa.indexOf('.');
    // Infixing a clamped mantissa gives "0R0087"; keep the point and suffix the letter instead.
    if (dot < 0 || clamped) return mantissa + letter;
    return mantissa.slice(0, dot) + letter + mantissa.slice(dot + 1);
  }

  if (!family.baseUnit && !prefix) return mantissa;
  return `${mantissa} ${prefix}${family.baseUnit}`;
}

/**
 * Parse what the user typed back to the family's base unit; null when it matches no accepted form
 * (which is an ordinary outcome — the value then stays text). Mirrors MetricUnitParser exactly:
 * bare number, number+unit, number+prefix+unit, number+bare-letter ("47k"), and RKM infix ("4k7").
 *
 * The window binds the bare letter only. "15 mΩ" parses — where the symbol is written out the
 * reader and the parser see the same thing, and both the component cache and the datasheet
 * extractor emit that form for the sub-ohm values (RDS(on), ESR, DCR) that are ordinary here.
 */
export function parseFamilyValue(raw: string, familyCode: string | null | undefined): number | null {
  const family = unitFamily(familyCode);
  if (!family || raw == null) return null;
  let s = raw.trim();
  if (s === '') return null;
  if (s.toLowerCase().startsWith('null..')) s = s.slice(6).trim();

  const numMatch = /^[-+]?(\d+\.?\d*|\.\d+)([eE][-+]?\d+)?/.exec(s);
  if (!numMatch) return null;
  const numText = numMatch[0];
  const rest = s.slice(numText.length).trim();

  const allowed = (exp: number, bareLetter: boolean) =>
    exp === 0 || !(bareLetter || (family.minExp === 0 && family.maxExp === 0))
      || (exp >= family.minExp && exp <= family.maxExp);

  // A letter standing alone: a single prefix char, or the base marker / unit symbol ("no scaling").
  const letterExp = (letter: string): number | null => {
    if (!letter) return null;
    if (letter === family.baseMarker) return 0;
    if (family.baseUnit && letter.toLowerCase() === family.baseUnit.toLowerCase()) return 0;
    return letter.length === 1 && letter in LETTER_EXP ? LETTER_EXP[letter] : null;
  };

  // RKM infix: "4k7" — the letter is the decimal point. Only a plain integer mantissa qualifies;
  // "4.7k7" is a typo, not a number.
  const rkmMatch = /^([^0-9.\s]+)(\d+)$/.exec(rest);
  if (rkmMatch && /^[-+]?\d+$/.test(numText)) {
    const exp = letterExp(rkmMatch[1]);
    if (exp == null || !allowed(exp, true)) return null;
    const sign = numText.startsWith('-') ? -1 : 1;
    const mantissa = parseFloat(numText.replace(/^[-+]/, '') + '.' + rkmMatch[2]);
    return clean(sign * mantissa * Math.pow(10, exp));
  }

  const num = parseFloat(numText);
  if (isNaN(num)) return null;

  // The unit written out: "", "Ω", "kΩ", "mA" — never subject to the window.
  if (rest === '' || (family.baseUnit && rest.toLowerCase() === family.baseUnit.toLowerCase())) {
    return clean(num);
  }
  if (family.baseUnit && rest.length === family.baseUnit.length + 1
      && rest.slice(1).toLowerCase() === family.baseUnit.toLowerCase() && rest[0] in LETTER_EXP) {
    return clean(num * Math.pow(10, LETTER_EXP[rest[0]]));
  }

  // A bare letter: "47k", "100n", "100R".
  const exp = letterExp(rest);
  if (exp == null || !allowed(exp, true)) return null;
  return clean(num * Math.pow(10, exp));
}
