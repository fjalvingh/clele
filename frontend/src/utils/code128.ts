/**
 * Code 128 (subset B) encoder plus the two renderers the label printing paths need, kept in one
 * module so the canvas output (daemon path) and the SVG output (browser path) can never drift.
 *
 * The barcodes we print identify a part *in this application*: `CLE-` + the zero-padded part id.
 * The prefix is what makes our own label distinguishable from a manufacturer's or distributor's
 * barcode, so the scanner can jump straight to the part instead of guessing (see BarcodeScanner).
 */

export const BARCODE_PREFIX = 'CLE-';

/** The barcode string for a part, e.g. 123 -> "CLE-000123". */
export function partBarcode(partId: number): string {
  return `${BARCODE_PREFIX}${String(partId).padStart(6, '0')}`;
}

/**
 * The part id encoded in one of our barcodes, or null when the code isn't ours (a manufacturer or
 * distributor code). Deliberately tolerant: scanners vary in case handling and some wrap the
 * payload in braces or pad it with whitespace.
 */
export function parsePartBarcode(code: string): number | null {
  const m = /^CLE-(\d{1,12})$/i.exec(code.trim().replace(/[{}\s]/g, ''));
  if (!m) return null;
  const id = Number(m[1]);
  return Number.isSafeInteger(id) && id > 0 ? id : null;
}

// The 107 standard Code 128 symbol patterns, each digit a run length starting with a bar. Values
// 0-102 are the shared character set, 103-105 the start codes, 106 the stop (13 modules, the only
// pattern with a trailing bar).
const PATTERNS = [
  '212222', '222122', '222221', '121223', '121322', '131222', '122213', '122312', '132212', '221213',
  '221312', '231212', '112232', '122132', '122231', '113222', '123122', '123221', '223211', '221132',
  '221231', '213212', '223112', '312131', '311222', '321122', '321221', '312212', '322112', '322211',
  '212123', '212321', '232121', '111323', '131123', '131321', '112313', '132113', '132311', '211313',
  '231113', '231311', '112133', '112331', '132131', '113123', '113321', '133121', '313121', '211331',
  '231131', '213113', '213311', '213131', '311123', '311321', '331121', '312113', '312311', '332111',
  '314111', '221411', '431111', '111224', '111422', '121124', '121421', '141122', '141221', '112214',
  '112412', '122114', '122411', '142112', '142211', '241211', '221114', '413111', '241112', '134111',
  '111242', '121142', '121241', '114212', '124112', '124211', '411212', '421112', '421211', '212141',
  '214121', '412121', '111143', '111341', '131141', '114113', '114311', '411113', '411311', '113141',
  '114131', '311141', '411131', '211412', '211214', '211232', '2331112',
];

const START_B = 104;
const STOP = 106;

/**
 * Encodes text as Code 128 subset B, returning one entry per module: true = bar, false = space.
 * Subset B covers ASCII 32-126, which is everything our `CLE-000123` codes use.
 */
export function code128bModules(text: string): boolean[] {
  const values: number[] = [START_B];
  for (const ch of text) {
    const v = ch.charCodeAt(0) - 32;
    if (v < 0 || v > 94) throw new Error(`Character not encodable in Code 128 B: ${ch}`);
    values.push(v);
  }
  // Checksum: start value plus each data value weighted by its 1-based position, modulo 103.
  let sum = START_B;
  for (let i = 1; i < values.length; i++) sum += i * values[i];
  values.push(sum % 103);
  values.push(STOP);

  const modules: boolean[] = [];
  for (const value of values) {
    let bar = true;
    for (const run of PATTERNS[value]) {
      const width = Number(run);
      for (let i = 0; i < width; i++) modules.push(bar);
      bar = !bar;
    }
  }
  return modules;
}

/** Quiet zone required either side of the symbol, in modules. 10 is the standard minimum. */
export const QUIET_ZONE_MODULES = 10;

/**
 * Largest integer module width (in device dots/pixels) that fits the symbol plus its quiet zones
 * into `availablePx`, or null when even the narrowest usable bar won't fit. Module widths must be
 * whole dots: a fractional width makes the printer round bars unevenly, which scanners misread.
 */
export function pickModuleWidth(
  moduleCount: number,
  availablePx: number,
  minPx = 2,
  maxPx = 4,
): number | null {
  const total = moduleCount + 2 * QUIET_ZONE_MODULES;
  const fit = Math.floor(availablePx / total);
  if (fit < minPx) return null;
  return Math.min(maxPx, fit);
}

/** Draws the symbol onto a canvas: bars only, so the caller controls the background. */
export function drawCode128(
  ctx: CanvasRenderingContext2D,
  modules: boolean[],
  x: number,
  y: number,
  moduleWidthPx: number,
  heightPx: number,
) {
  // Draw each run of bars as one rect so adjacent bars never show a seam from rounding.
  let i = 0;
  while (i < modules.length) {
    if (!modules[i]) {
      i++;
      continue;
    }
    let run = 0;
    while (i + run < modules.length && modules[i + run]) run++;
    ctx.fillRect(x + i * moduleWidthPx, y, run * moduleWidthPx, heightPx);
    i += run;
  }
}

/**
 * The symbol as a standalone inline `<svg>` sized in millimetres, for the browser print path.
 * `moduleWidthMm` keeps the bars identical to what the daemon path renders when both are given the
 * same physical geometry.
 */
export function code128Svg(modules: boolean[], moduleWidthMm: number, heightMm: number): string {
  const widthMm = modules.length * moduleWidthMm;
  const rects: string[] = [];
  let i = 0;
  while (i < modules.length) {
    if (!modules[i]) {
      i++;
      continue;
    }
    let run = 0;
    while (i + run < modules.length && modules[i + run]) run++;
    rects.push(
      `<rect x="${(i * moduleWidthMm).toFixed(3)}" y="0" width="${(run * moduleWidthMm).toFixed(3)}" height="${heightMm}" />`,
    );
    i += run;
  }
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${widthMm.toFixed(3)}mm" height="${heightMm}mm" viewBox="0 0 ${widthMm.toFixed(3)} ${heightMm}" shape-rendering="crispEdges" fill="#000">${rects.join('')}</svg>`;
}
