import { getFootprints } from '../api';
import type { Footprint } from '../api/types';

/**
 * The comparison the backend uses (`FootprintService.matchKey`): lower case, letters and digits
 * only, so "SOT23", "sot-23" and "SOT 23" are one footprint. A spelling with no letters or digits
 * keys on itself.
 */
export function footprintKey(spelling: string): string {
  const key = spelling.replace(/[^A-Za-z0-9]/g, '').toLowerCase();
  return key === '' ? spelling.trim() : key;
}

const CJK = /[\u3000-\u303F\u3040-\u30FF\u3400-\u9FFF\uAC00-\uD7AF\uF900-\uFAFF\uFF00-\uFFEF]/;
const NOT_A_PACKAGE = new Set([
  'smd', 'smt', 'tht', 'throughhole', 'thruhole', 'plugin', 'other', 'others',
  'na', 'none', 'unknown', 'custom',
]);

/**
 * Whether a spelling names a package at all — `FootprintService.isPackageName`, which turns
 * "插件" (through-hole), "SMD" or "SMD,19.2x18mm" into no footprint rather than a list entry.
 */
export function isPackageName(spelling: string): boolean {
  if (CJK.test(spelling) || /^\s*SMD\s*,/i.test(spelling)) return false;
  const key = spelling.replace(/[^A-Za-z0-9]/g, '').toLowerCase();
  return key !== '' && !NOT_A_PACKAGE.has(key);
}

/**
 * The canonical name the backend will store a spelling as, given an index from match key to name
 * — falling back, as `FootprintService.match` does, to the part before a trailing "(...)".
 */
export function lookupFootprint(index: Map<string, string>, spelling: string): string | undefined {
  const hit = index.get(footprintKey(spelling));
  if (hit) return hit;
  const parentheses = /^(.*?)\s*\([^()]*\)\s*$/.exec(spelling);
  return parentheses && parentheses[1].trim() ? index.get(footprintKey(parentheses[1])) : undefined;
}

// One fetch per page load, shared by every input on the page. Cleared on failure so a later mount
// can retry.
let cache: Promise<Footprint[]> | null = null;

export function loadFootprints(): Promise<Footprint[]> {
  if (!cache) {
    cache = getFootprints().catch((e) => {
      cache = null;
      throw e;
    });
  }
  return cache;
}

/** Forget the cached list — after the Footprints screen changed it. */
export function invalidateFootprints() {
  cache = null;
}
