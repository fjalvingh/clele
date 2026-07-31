import { createPrintJob, getPrintJobStatus } from '../api';

// Physical label size. A typical roll/tape is 50 × 18 mm; tweak here if you change media.
// Shared by the browser print path (PrintLabelModal) and the daemon print path (both the modal
// and the Profile page's "Test print" button), so a label always prints at the same size
// regardless of which method delivers it.
export const LABEL_W_MM = 50;
export const LABEL_H_MM = 18;

// DPI the daemon-rendered PNG is rasterized at, matching the Brother QL-710W's native resolution.
const DAEMON_DPI = 300;
const PX_PER_MM = DAEMON_DPI / 25.4;

// Renders label content (part number + optional description) onto an offscreen canvas PNG, for
// the daemon print path (which needs a bitmap, not a browser-printable HTML document).
export function renderLabelToPngDataUrl(title: string, description?: string): string {
  const w = Math.round(LABEL_W_MM * PX_PER_MM);
  const h = Math.round(LABEL_H_MM * PX_PER_MM);
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

// Renders a label and sends it to the given daemon, reporting progress via onUpdate.
export async function printLabelViaDaemon(
  daemonId: number,
  title: string,
  description: string | undefined,
  onUpdate: (state: DaemonPrintState, error?: string) => void,
) {
  onUpdate('sending');
  try {
    const dataUrl = renderLabelToPngDataUrl(title, description);
    const base64 = dataUrl.split(',')[1];
    const job = await createPrintJob(daemonId, base64);
    onUpdate('printing');
    await pollJobStatus(job.id, onUpdate);
  } catch (err) {
    onUpdate('failed', (err as Error).message);
  }
}
