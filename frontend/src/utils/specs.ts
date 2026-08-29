/**
 * The AI part search returns specifications as an array of "jsonName: value" strings
 * (see AiPartSearchService's SYSTEM_PROMPT_TEMPLATE, which instructs the model to key them
 * by each spec definition's exact jsonName). Both entry paths — Quick Add and the barcode
 * scanner — need them as the `Record<string, string>` that `QuickAddRequest.specs` takes.
 *
 * Keys that match no spec definition are kept: the backend's
 * `SpecDefinitionService.canonicalizeKeys` maps known aliases onto their canonical name and
 * deliberately lets unrecognised keys through, so a later "rescan from parts" can turn them
 * into definitions.
 */
export function parseAiSpecs(raw: string[] | undefined): Record<string, string> {
  const specs: Record<string, string> = {};
  for (const entry of raw ?? []) {
    // Split on the FIRST colon only — values legitimately contain colons ("1:10", "0:00").
    const idx = entry.indexOf(':');
    if (idx === -1) continue;
    const key = entry.slice(0, idx).trim();
    const value = entry.slice(idx + 1).trim();
    if (key !== '' && value !== '') specs[key] = value;
  }
  return specs;
}

/**
 * A numeric spec value as the three figures a datasheet states: a nominal with an optional band
 * around it.
 *
 * Every field is the *stored* form — a base-SI-unit number as a string, or empty when absent — so
 * these travel between the wire value and the input boxes without going near a rendering.
 */
export interface SpecNumberParts {
  min: string;
  nominal: string;
  max: string;
}

const EMPTY_PARTS: SpecNumberParts = { min: '', nominal: '', max: '' };

/** "null" is how an open bound is written on the wire — Partsbox's spelling, kept by the backend. */
const bound = (s: string) => (s.trim() === '' || s.trim() === 'null' ? '' : s.trim());

/**
 * Split a stored numeric spec value into min / nominal / max.
 *
 * Three spellings arrive, and all three are the backend's (`PartSpecValueService.valueOf`):
 * a bare number is a nominal, `"min..max"` is a band with no nominal, and `"min..nominal..max"`
 * is both. Anything else — a text value, a half-typed number — comes back as the nominal, which is
 * what keeps this safe to call on a value whose spec turns out not to be numeric after all.
 */
export function splitSpecNumber(raw: string | number | null | undefined): SpecNumberParts {
  const value = String(raw ?? '').trim();
  if (value === '') return { ...EMPTY_PARTS };
  const parts = value.split('..');
  if (parts.length === 3) {
    return { min: bound(parts[0]), nominal: bound(parts[1]), max: bound(parts[2]) };
  }
  if (parts.length === 2) {
    return { min: bound(parts[0]), nominal: '', max: bound(parts[1]) };
  }
  return { min: '', nominal: value, max: '' };
}

/**
 * The inverse: the shortest spelling that carries what is filled in.
 *
 * A value with no bounds stays a bare number, and a band with no nominal stays the two-part form
 * the catalogue is already full of — so switching a field to the three-box editor and back leaves
 * the value byte-for-byte as it was found.
 */
export function joinSpecNumber(parts: SpecNumberParts): string {
  const min = bound(parts.min);
  const nominal = bound(parts.nominal);
  const max = bound(parts.max);
  const write = (s: string) => (s === '' ? 'null' : s);
  if (min === '' && max === '') return nominal;
  if (nominal === '') return `${write(min)}..${write(max)}`;
  return `${write(min)}..${write(nominal)}..${write(max)}`;
}

/** Does this value carry a band, and so have to be edited as three boxes? */
export function hasSpecBounds(raw: string | number | null | undefined): boolean {
  const parts = splitSpecNumber(raw);
  return parts.min !== '' || parts.max !== '';
}
