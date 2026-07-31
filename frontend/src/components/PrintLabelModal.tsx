import { useEffect, useMemo, useState } from 'react';
import { getPrintDaemons } from '../api';
import type { Part, PrintDaemon } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import {
  LABEL_H_MM,
  LABEL_W_MM,
  labelSizeFor,
  printLabelViaDaemon,
  type DaemonPrintState,
} from '../utils/labelPrint';
import Modal from './Modal';

// Both the Dymo LabelWriter 320 and the Brother QL-710W install as ordinary system printers, so
// browser printing goes through the print dialog (the user picks the right printer there) with
// @page driving the page size — no drivers or backend printing needed.
//
// The preview is drawn at the size actually being printed: the daemon's detected media when
// printing via a daemon, otherwise the browser default. Preview zoom is capped so a long label
// (e.g. 54mm die-cut) can't overflow the dialog.
const PREVIEW_MAX_SCALE = 3; // on-screen zoom so a small label is readable
// Keeps the scaled preview inside the modal: Modal is max-w-lg (512px) with px-6 padding, so the
// usable width is ~464px. 100mm is ~378px at the CSS 96dpi reference, leaving room to spare.
const PREVIEW_MAX_WIDTH_MM = 100;

function escapeHtml(s: string): string {
  return s
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

// A complete, self-contained HTML document for one label. Used both for the on-screen preview
// (rendered into an isolated iframe so the app's CSS can't leak in) and for the actual print.
// The description simply overflows-hidden: whatever fits on the label shows, the rest is clipped.
function buildLabelDoc(part: Part, widthMm: number, heightMm: number): string {
  const partNumber = escapeHtml(part.partNumber ?? '');
  const description = escapeHtml(part.description ?? '');
  return `<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8" />
<style>
  @page { size: ${widthMm}mm ${heightMm}mm; margin: 0; }
  html, body { margin: 0; padding: 0; background: #fff; }
  .label {
    box-sizing: border-box;
    width: ${widthMm}mm;
    height: ${heightMm}mm;
    padding: 1mm 1.5mm;
    overflow: hidden;
    display: flex;
    flex-direction: column;
    font-family: Arial, Helvetica, sans-serif;
    color: #000;
  }
  .pn {
    font-weight: 700;
    font-size: 11pt;
    line-height: 1.1;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .desc {
    margin-top: 0.6mm;
    font-size: 7pt;
    line-height: 1.18;
    overflow: hidden;
    flex: 1;
  }
</style>
</head>
<body>
  <div class="label">
    <div class="pn">${partNumber}</div>
    ${description ? `<div class="desc">${description}</div>` : ''}
  </div>
</body>
</html>`;
}

// Print by writing the label document into a hidden iframe and calling its print(). This keeps
// the rest of the SPA out of the printed output and avoids popup blockers.
function printLabel(doc: string) {
  const iframe = document.createElement('iframe');
  iframe.setAttribute('aria-hidden', 'true');
  iframe.style.cssText = 'position:fixed;right:0;bottom:0;width:0;height:0;border:0;';
  iframe.srcdoc = doc;
  iframe.onload = () => {
    const win = iframe.contentWindow;
    if (win) {
      win.focus();
      win.print();
    }
    // The print dialog is modal; clean up once it's been shown.
    setTimeout(() => iframe.remove(), 1000);
  };
  document.body.appendChild(iframe);
}

interface Props {
  open: boolean;
  onClose: () => void;
  part: Part;
}

export default function PrintLabelModal({ open, onClose, part }: Props) {
  const { user } = useAuth();
  const [daemonState, setDaemonState] = useState<DaemonPrintState>('idle');
  const [daemonError, setDaemonError] = useState<string | null>(null);
  const [daemons, setDaemons] = useState<PrintDaemon[]>([]);

  const useDaemon = user?.printMethod === 'DAEMON' && !!user.preferredDaemonId;

  // Load daemons only when the modal opens in daemon mode — needed for the detected media size.
  useEffect(() => {
    if (!open || !useDaemon) return;
    getPrintDaemons()
      .then(setDaemons)
      .catch(() => setDaemons([]));
  }, [open, useDaemon]);

  // The size actually being printed: the daemon's detected media, or the browser default. Drives
  // both the preview and the printed output, so what's shown is what comes out.
  const daemon = useDaemon ? daemons.find((d) => d.id === user?.preferredDaemonId) : undefined;
  const { widthMm, heightMm } = useDaemon
    ? labelSizeFor(daemon)
    : { widthMm: LABEL_W_MM, heightMm: LABEL_H_MM };

  const doc = useMemo(
    () => buildLabelDoc(part, widthMm, heightMm),
    [part, widthMm, heightMm],
  );

  // Zoom enough to be readable, but never wider than the dialog.
  const previewScale = Math.min(PREVIEW_MAX_SCALE, PREVIEW_MAX_WIDTH_MM / widthMm);

  const printViaDaemon = async () => {
    if (!user?.preferredDaemonId) return;
    setDaemonError(null);
    await printLabelViaDaemon(
      user.preferredDaemonId,
      part.partNumber,
      part.description,
      (state, error) => {
        setDaemonState(state);
        if (error) setDaemonError(error);
      },
      { widthMm, heightMm },
    );
  };

  return (
    <Modal open={open} onClose={onClose} title="Print label">
      <p className="mb-3 text-sm text-gray-600">
        Preview of the {widthMm} × {heightMm} mm label. The part number is on top, with as much of
        the description as fits below.
      </p>

      {/* Actual-size label rendered in an isolated iframe, scaled up for readability. */}
      <div className="mb-2 flex max-w-full justify-center overflow-hidden">
        <div
          className="rounded border border-gray-300 shadow-sm"
          style={{
            width: `${widthMm * previewScale}mm`,
            height: `${heightMm * previewScale}mm`,
            maxWidth: '100%',
          }}
        >
          <iframe
            title="Label preview"
            srcDoc={doc}
            scrolling="no"
            style={{
              width: `${widthMm}mm`,
              height: `${heightMm}mm`,
              transform: `scale(${previewScale})`,
              transformOrigin: 'top left',
              border: 'none',
              pointerEvents: 'none',
            }}
          />
        </div>
      </div>
      <p className="mb-4 text-center text-xs text-gray-400">
        Shown at {previewScale.toFixed(1)}× — actual size {widthMm} × {heightMm} mm
        {useDaemon && daemon?.mediaDescription ? ` on ${daemon.mediaDescription}` : ''}
      </p>

      {useDaemon ? (
        <>
          <p className="mb-4 text-xs text-gray-500">
            Prints silently through your paired daemon — no dialog will appear.
          </p>
          {daemonState === 'printing' && (
            <p className="mb-3 text-sm text-blue-600">Sending to the printer…</p>
          )}
          {daemonState === 'done' && <p className="mb-3 text-sm text-green-600">Printed.</p>}
          {daemonState === 'failed' && (
            <p className="mb-3 text-sm text-red-600">Failed to print{daemonError ? `: ${daemonError}` : '.'}</p>
          )}
        </>
      ) : (
        <p className="mb-4 text-xs text-gray-500">
          Pick your label printer (Dymo LabelWriter 320 or Brother QL-710W) in the print dialog. Set
          margins to <span className="font-medium">None</span> and scale to{' '}
          <span className="font-medium">100%</span> for an exact fit.
        </p>
      )}

      <div className="flex justify-end gap-3">
        <button
          onClick={onClose}
          className="rounded-lg border border-gray-300 px-4 py-2 text-sm hover:bg-gray-50"
        >
          Cancel
        </button>
        {useDaemon ? (
          <button
            onClick={printViaDaemon}
            disabled={daemonState === 'sending' || daemonState === 'printing'}
            className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-50"
          >
            {daemonState === 'sending' || daemonState === 'printing' ? 'Printing…' : 'Print'}
          </button>
        ) : (
          <button
            onClick={() => printLabel(doc)}
            className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700"
          >
            🏷️ Print
          </button>
        )}
      </div>
    </Modal>
  );
}
