import { createPrintJob, getPrintJobStatus } from '../api';
import { code128bModules, drawCode128, pickModuleWidth } from './code128';

// Fallback label size for the browser print path, which has no way to ask the printer what is
// loaded — the user picks the printer in the OS dialog. The daemon path does not use this: it
// renders to the media the daemon detected over IPP (see labelSizeFor).
export const LABEL_W_MM = 50;
export const LABEL_H_MM = 18;

// Brother QL geometry, kept ONLY as a fallback for a daemon too old to report its printable area
// (one predating the X-Printer-Printable-* headers). Mirrors internal/qlraster/raster.go. New code
// must not reach for these: every driver now reports the real printable area, the Brother from
// these measured constants and the Dymo from the margins CUPS reports, so the frontend no longer
// has to mirror per-printer numbers it cannot verify.
const LEGACY_QL_DIE_CUT_LEAD_MM = 6;
const LEGACY_QL_UNPRINTABLE_EDGE_MM = 2;

/**
 * Physical size to render for a daemon.
 *
 * The printer reports the area it can actually mark: a width across the print head and a length
 * along the feed. A label runs long-edge left-to-right, so the reported *length* becomes our width
 * and the reported *width* becomes our height.
 *
 * Three tiers, most trustworthy first:
 *   1. the printable area the daemon reported;
 *   2. detected media minus the legacy Brother constants, for a daemon not yet upgraded;
 *   3. the browser default, when the daemon has reported nothing at all.
 */
export function labelSizeFor(daemon?: {
  printableWidthMm?: number;
  printableLengthMm?: number;
  mediaWidthMm?: number;
  mediaLengthMm?: number;
  mediaKind?: string;
}): { widthMm: number; heightMm: number } {
  if (daemon?.printableWidthMm) {
    return {
      // No printable length means continuous stock: the roll fixes the width, the length is ours.
      widthMm: daemon.printableLengthMm ? round1(daemon.printableLengthMm) : LABEL_W_MM,
      heightMm: round1(daemon.printableWidthMm),
    };
  }
  if (daemon?.mediaWidthMm) {
    const printableHeight = Math.max(1, round1(daemon.mediaWidthMm - LEGACY_QL_UNPRINTABLE_EDGE_MM));
    if (daemon.mediaKind === 'DIE_CUT' && daemon.mediaLengthMm) {
      return {
        widthMm: Math.max(1, round1(daemon.mediaLengthMm - LEGACY_QL_DIE_CUT_LEAD_MM)),
        heightMm: printableHeight,
      };
    }
    // Continuous tape: the width is fixed by the roll, the length is ours to choose.
    return { widthMm: LABEL_W_MM, heightMm: printableHeight };
  }
  return { widthMm: LABEL_W_MM, heightMm: LABEL_H_MM };
}

// Label sizes are now fractional — Dymo stock is sized in inches, so a printable area of
// 43.52 mm is normal. One decimal is well under a single 300 dpi dot (0.085 mm) and keeps the
// numbers short where they are shown to the user and interpolated into `@page size:`.
const round1 = (mm: number) => Math.round(mm * 10) / 10;

// DPI the daemon-rendered PNG is rasterized at, matching the Brother QL-710W's native resolution.
const DAEMON_DPI = 300;
const PX_PER_MM = DAEMON_DPI / 25.4;

// Renders label content (part number + optional description) onto an offscreen canvas PNG, for
// the daemon print path (which needs a bitmap, not a browser-printable HTML document), at the
// given physical size in millimetres.
export function renderLabelToPngDataUrl(
  title: string,
  description?: string,
  widthMm: number = LABEL_W_MM,
  heightMm: number = LABEL_H_MM,
): string {
  const w = Math.round(widthMm * PX_PER_MM);
  const h = Math.round(heightMm * PX_PER_MM);
  const canvas = document.createElement('canvas');
  canvas.width = w;
  canvas.height = h;
  const ctx = canvas.getContext('2d');
  if (!ctx) throw new Error('Canvas not supported');

  ctx.fillStyle = '#fff';
  ctx.fillRect(0, 0, w, h);
  ctx.fillStyle = '#000';
  ctx.textBaseline = 'top';

  const padX = 1.5 * PX_PER_MM;
  const padY = 1 * PX_PER_MM;
  const pnSize = 11 * (DAEMON_DPI / 72);
  const descSize = 7 * (DAEMON_DPI / 72);

  ctx.font = `700 ${pnSize}px Arial, Helvetica, sans-serif`;
  ctx.fillText(title ?? '', padX, padY, w - 2 * padX);

  if (description) {
    ctx.font = `${descSize}px Arial, Helvetica, sans-serif`;
    const lineHeight = descSize * 1.18;
    const maxWidth = w - 2 * padX;
    const maxY = h - padY;
    let y = padY + pnSize * 1.1 + 0.6 * PX_PER_MM;
    const words = description.split(/\s+/);
    let line = '';
    for (const word of words) {
      const candidate = line ? `${line} ${word}` : word;
      if (ctx.measureText(candidate).width > maxWidth && line) {
        if (y + lineHeight > maxY) break;
        ctx.fillText(line, padX, y, maxWidth);
        y += lineHeight;
        line = word;
      } else {
        line = candidate;
      }
    }
    if (line && y + lineHeight <= maxY) {
      ctx.fillText(line, padX, y, maxWidth);
    }
  }

  return canvas.toDataURL('image/png');
}

// Label padding, shared by the text and barcode labels.
const PAD_X_MM = 1.5;
const PAD_Y_MM = 1;

/**
 * Whether a scannable barcode fits on a label of this width. Below the minimum bar width scanners
 * start misreading, so it is better to say so than to print an unreadable label.
 */
export function barcodeFits(code: string, widthMm: number): boolean {
  const modules = code128bModules(code).length;
  const availablePx = (widthMm - 2 * PAD_X_MM) * PX_PER_MM;
  return pickModuleWidth(modules, availablePx) !== null;
}

/**
 * Module width in mm for a barcode on a label of this width, or null when it doesn't fit. The
 * browser (SVG) path uses this so its bars land on exactly the same geometry as the daemon path.
 */
export function barcodeModuleWidthMm(code: string, widthMm: number): number | null {
  const modules = code128bModules(code).length;
  const availablePx = (widthMm - 2 * PAD_X_MM) * PX_PER_MM;
  const dots = pickModuleWidth(modules, availablePx);
  return dots === null ? null : dots / PX_PER_MM;
}

/** Height of the bars on a barcode label, in mm — the rest is padding and the readable code. */
export function barcodeBarHeightMm(heightMm: number): number {
  return Math.max(3, heightMm - 2 * PAD_Y_MM - BARCODE_TEXT_MM);
}

// Space reserved under the bars for the human-readable code (~7pt line plus a little air).
const BARCODE_TEXT_MM = 3.2;

/**
 * Renders a barcode-only label (bars plus the code printed underneath) for the daemon path. Falls
 * back to the code as plain text if the label is too narrow for a scannable symbol.
 */
export function renderBarcodeLabelToPngDataUrl(
  code: string,
  widthMm: number = LABEL_W_MM,
  heightMm: number = LABEL_H_MM,
): string {
  const w = Math.round(widthMm * PX_PER_MM);
  const h = Math.round(heightMm * PX_PER_MM);
  const canvas = document.createElement('canvas');
  canvas.width = w;
  canvas.height = h;
  const ctx = canvas.getContext('2d');
  if (!ctx) throw new Error('Canvas not supported');

  ctx.fillStyle = '#fff';
  ctx.fillRect(0, 0, w, h);
  ctx.fillStyle = '#000';
  ctx.textBaseline = 'top';
  ctx.textAlign = 'center';

  const padX = PAD_X_MM * PX_PER_MM;
  const padY = PAD_Y_MM * PX_PER_MM;
  const textSize = 7 * (DAEMON_DPI / 72);
  const modules = code128bModules(code);
  const moduleWidth = pickModuleWidth(modules.length, w - 2 * padX);

  if (moduleWidth === null) {
    // Too narrow for bars — at least print something readable rather than an unscannable symbol.
    ctx.font = `700 ${textSize}px Arial, Helvetica, sans-serif`;
    ctx.fillText(code, w / 2, (h - textSize) / 2, w - 2 * padX);
    return canvas.toDataURL('image/png');
  }

  const barHeight = Math.max(3 * PX_PER_MM, h - 2 * padY - BARCODE_TEXT_MM * PX_PER_MM);
  // Centre the symbol; its quiet zones are white space either side, which the padding provides.
  const symbolWidth = modules.length * moduleWidth;
  const x = Math.round((w - symbolWidth) / 2);
  drawCode128(ctx, modules, x, padY, moduleWidth, barHeight);

  ctx.font = `${textSize}px Arial, Helvetica, sans-serif`;
  ctx.fillText(code, w / 2, padY + barHeight + 0.4 * PX_PER_MM, w - 2 * padX);

  return canvas.toDataURL('image/png');
}

export type DaemonPrintState = 'idle' | 'sending' | 'printing' | 'done' | 'failed';

async function pollJobStatus(jobId: number, onUpdate: (state: DaemonPrintState, error?: string) => void) {
  const deadline = Date.now() + 20000;
  while (Date.now() < deadline) {
    const job = await getPrintJobStatus(jobId);
    if (job.status === 'DONE') return onUpdate('done');
    if (job.status === 'FAILED') return onUpdate('failed', job.errorMessage);
    onUpdate('printing');
    await new Promise((r) => setTimeout(r, 1000));
  }
  onUpdate('failed', 'Timed out waiting for the daemon to print');
}

// Queues a rendered label with the daemon and follows it to completion. Shared by every label
// kind — the backend only ever sees a PNG.
async function sendPngToDaemon(
  daemonId: number,
  dataUrl: string,
  onUpdate: (state: DaemonPrintState, error?: string) => void,
) {
  onUpdate('sending');
  try {
    const base64 = dataUrl.split(',')[1];
    const job = await createPrintJob(daemonId, base64);
    onUpdate('printing');
    await pollJobStatus(job.id, onUpdate);
  } catch (err) {
    onUpdate('failed', (err as Error).message);
  }
}

// Renders a label at the daemon's detected media size and sends it, reporting progress via
// onUpdate.
export async function printLabelViaDaemon(
  daemonId: number,
  title: string,
  description: string | undefined,
  onUpdate: (state: DaemonPrintState, error?: string) => void,
  size: { widthMm: number; heightMm: number } = { widthMm: LABEL_W_MM, heightMm: LABEL_H_MM },
) {
  let dataUrl: string;
  try {
    dataUrl = renderLabelToPngDataUrl(title, description, size.widthMm, size.heightMm);
  } catch (err) {
    return onUpdate('failed', (err as Error).message);
  }
  await sendPngToDaemon(daemonId, dataUrl, onUpdate);
}

/** Prints the part's barcode on its own label through the daemon. */
export async function printBarcodeLabelViaDaemon(
  daemonId: number,
  code: string,
  onUpdate: (state: DaemonPrintState, error?: string) => void,
  size: { widthMm: number; heightMm: number } = { widthMm: LABEL_W_MM, heightMm: LABEL_H_MM },
) {
  let dataUrl: string;
  try {
    dataUrl = renderBarcodeLabelToPngDataUrl(code, size.widthMm, size.heightMm);
  } catch (err) {
    return onUpdate('failed', (err as Error).message);
  }
  await sendPngToDaemon(daemonId, dataUrl, onUpdate);
}
