import { createPrintJob, getPrintJobStatus } from '../api';
import { code128bModules, drawCode128, pickModuleWidth } from './code128';

// Fallback label size for the browser print path, which has no way to ask the printer what is
// loaded — the user picks the printer in the OS dialog. The daemon path does not use this: it
// renders to the media the daemon detected over IPP (see labelSizeFor).
export const LABEL_W_MM = 50;
export const LABEL_H_MM = 18;

// Printer geometry measured on the QL-710W — must match the daemon's constants in
// internal/qlraster/raster.go. The printer feeds a lead before printing and cannot reach the far
// edge across the head, so the printable area is smaller than the physical label. Rendering to
// the printable area (rather than the full label) means nothing is clipped.
const DIE_CUT_LEAD_MM = 6;
const UNPRINTABLE_EDGE_MM = 2;

/**
 * Physical size to render for a daemon, from the media it detected in the printer. Die-cut labels
 * print along their length, so the longer dimension runs left-to-right on the label. Falls back to
 * the browser default when the daemon hasn't reported media yet.
 */
export function labelSizeFor(daemon?: {
  mediaWidthMm?: number;
  mediaLengthMm?: number;
  mediaKind?: string;
}): { widthMm: number; heightMm: number } {
  if (!daemon?.mediaWidthMm) {
    return { widthMm: LABEL_W_MM, heightMm: LABEL_H_MM };
  }
  const printableHeight = Math.max(1, daemon.mediaWidthMm - UNPRINTABLE_EDGE_MM);
  if (daemon.mediaKind === 'DIE_CUT' && daemon.mediaLengthMm) {
    return {
      widthMm: Math.max(1, daemon.mediaLengthMm - DIE_CUT_LEAD_MM),
      heightMm: printableHeight,
    };
  }
  // Continuous tape: the width is fixed by the roll, the length is ours to choose.
  return { widthMm: LABEL_W_MM, heightMm: printableHeight };
}

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
