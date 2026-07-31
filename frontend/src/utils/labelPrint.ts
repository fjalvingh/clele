import { createPrintJob, getPrintJobStatus } from '../api';

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

// Renders a label at the daemon's detected media size and sends it, reporting progress via
// onUpdate.
export async function printLabelViaDaemon(
  daemonId: number,
  title: string,
  description: string | undefined,
  onUpdate: (state: DaemonPrintState, error?: string) => void,
  size: { widthMm: number; heightMm: number } = { widthMm: LABEL_W_MM, heightMm: LABEL_H_MM },
) {
  onUpdate('sending');
  try {
    const dataUrl = renderLabelToPngDataUrl(title, description, size.widthMm, size.heightMm);
    const base64 = dataUrl.split(',')[1];
    const job = await createPrintJob(daemonId, base64);
    onUpdate('printing');
    await pollJobStatus(job.id, onUpdate);
  } catch (err) {
    onUpdate('failed', (err as Error).message);
  }
}
