import { useEffect, useMemo, useState } from 'react';
import { getPrintDaemons, updatePrintingPreference } from '../api';
import type { Part, PrintDaemon } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { code128bModules, code128Svg, partBarcode } from '../utils/code128';
import {
  barcodeBarHeightMm,
  barcodeModuleWidthMm,
  LABEL_H_MM,
  LABEL_W_MM,
  labelSizeFor,
  printBarcodeLabelViaDaemon,
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

// A complete, self-contained HTML document holding one label per body. Used both for the on-screen
// previews (rendered into isolated iframes so the app's CSS can't leak in) and for the actual
// print. Multiple bodies become multiple pages — i.e. multiple labels out of a single print dialog.
function labelDocument(bodies: string[], widthMm: number, heightMm: number): string {
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
  .label + .label { page-break-before: always; break-before: page; }
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
  .barcode { align-items: center; justify-content: center; }
  .code {
    margin-top: 0.4mm;
    font-size: 7pt;
    line-height: 1;
    letter-spacing: 0.3mm;
  }
  .code-only { font-weight: 700; font-size: 9pt; }
</style>
</head>
<body>
${bodies.join('\n')}
</body>
</html>`;
}

// The text label: part number on top, as much of the description as fits below. The description
// simply overflows-hidden — whatever fits shows, the rest is clipped.
function textLabelBody(part: Part): string {
  const partNumber = escapeHtml(part.partNumber ?? '');
  const description = escapeHtml(part.description ?? '');
  return `  <div class="label">
    <div class="pn">${partNumber}</div>
    ${description ? `<div class="desc">${description}</div>` : ''}
  </div>`;
}

// The barcode label: the Code 128 symbol with the code printed underneath, so it stays usable when
// a scan fails. The bars are inline SVG at the same module geometry the daemon path rasterizes.
function barcodeLabelBody(code: string, widthMm: number, heightMm: number): string {
  const moduleWidthMm = barcodeModuleWidthMm(code, widthMm);
  if (moduleWidthMm === null) {
    return `  <div class="label barcode">
    <div class="code-only">${escapeHtml(code)}</div>
  </div>`;
  }
  const svg = code128Svg(code128bModules(code), moduleWidthMm, barcodeBarHeightMm(heightMm));
  return `  <div class="label barcode">
    ${svg}
    <div class="code">${escapeHtml(code)}</div>
  </div>`;
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
  const { user, refresh } = useAuth();
  const [daemonState, setDaemonState] = useState<DaemonPrintState>('idle');
  const [daemonError, setDaemonError] = useState<string | null>(null);
  const [daemons, setDaemons] = useState<PrintDaemon[]>([]);
  // The text label is the normal case, so it is always on when the dialog opens — unticking it is
  // for the odd job where only a fresh barcode is wanted. Unlike the barcode choice it isn't
  // remembered: the next print starts from "print the label" again.
  const [withText, setWithText] = useState(true);
  const [withBarcode, setWithBarcode] = useState(!!user?.printBarcodeLabel);
  const [printingStep, setPrintingStep] = useState<'text' | 'barcode' | null>(null);

  const useDaemon = user?.printMethod === 'DAEMON' && !!user.preferredDaemonId;
  const code = partBarcode(part.id);

  // Follow the saved barcode preference whenever the dialog is (re)opened.
  useEffect(() => {
    if (open) {
      setWithText(true);
      setWithBarcode(!!user?.printBarcodeLabel);
    }
  }, [open, user?.printBarcodeLabel]);

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

  const textDoc = useMemo(
    () => labelDocument([textLabelBody(part)], widthMm, heightMm),
    [part, widthMm, heightMm],
  );
  const barcodeDoc = useMemo(
    () => labelDocument([barcodeLabelBody(code, widthMm, heightMm)], widthMm, heightMm),
    [code, widthMm, heightMm],
  );
  // What actually gets printed in the browser path: one document, one dialog, one page per
  // selected label.
  const printDoc = useMemo(() => {
    const bodies: string[] = [];
    if (withText) bodies.push(textLabelBody(part));
    if (withBarcode) bodies.push(barcodeLabelBody(code, widthMm, heightMm));
    return labelDocument(bodies, widthMm, heightMm);
  }, [part, code, withText, withBarcode, widthMm, heightMm]);

  const barcodeTooNarrow = withBarcode && barcodeModuleWidthMm(code, widthMm) === null;
  const nothingSelected = !withText && !withBarcode;

  // Zoom enough to be readable, but never wider than the dialog.
  const previewScale = Math.min(PREVIEW_MAX_SCALE, PREVIEW_MAX_WIDTH_MM / widthMm);

  // Persist the choice so the next print starts the same way (see /api/profile/printing).
  const toggleBarcode = async (checked: boolean) => {
    setWithBarcode(checked);
    if (!user) return;
    try {
      await updatePrintingPreference({
        printMethod: user.printMethod ?? 'BROWSER',
        preferredDaemonId: user.preferredDaemonId ?? null,
        printBarcodeLabel: checked,
      });
      await refresh();
    } catch {
      // A failed save only loses the default; the current print still honours the checkbox.
    }
  };

  const printViaDaemon = async () => {
    if (!user?.preferredDaemonId) return;
    setDaemonError(null);
    let failed = false;
    const onUpdate = (state: DaemonPrintState, error?: string) => {
      setDaemonState(state);
      if (error) {
        failed = true;
        setDaemonError(error);
      }
    };

    if (withText) {
      setPrintingStep('text');
      await printLabelViaDaemon(
        user.preferredDaemonId,
        part.partNumber,
        part.description,
        onUpdate,
        { widthMm, heightMm },
      );
    }

    // The labels go out as separate jobs, in order — only start the second if the first worked.
    if (withBarcode && !failed) {
      setPrintingStep('barcode');
      await printBarcodeLabelViaDaemon(user.preferredDaemonId, code, onUpdate, { widthMm, heightMm });
    }
    setPrintingStep(null);
  };

  return (
    <Modal open={open} onClose={onClose} title="Print label">
      <p className="mb-3 text-sm text-gray-600">
        Preview of the {widthMm} × {heightMm} mm {withText && withBarcode ? 'labels' : 'label'}.
        {withText
          ? ' The part number is on top, with as much of the description as fits below.'
          : ''}
        {withBarcode ? ` The barcode goes on ${withText ? 'a second' : 'its own'} label.` : ''}
      </p>

      {/* Actual-size labels rendered in isolated iframes, scaled up for readability. */}
      <div className="mb-2 flex max-w-full flex-col items-center gap-2 overflow-hidden">
        {[
          ...(withText ? [{ title: 'Label preview', doc: textDoc }] : []),
          ...(withBarcode ? [{ title: 'Barcode label preview', doc: barcodeDoc }] : []),
        ].map((preview) => (
          <div
            key={preview.title}
            className="rounded border border-gray-300 shadow-sm"
            style={{
              width: `${widthMm * previewScale}mm`,
              height: `${heightMm * previewScale}mm`,
              maxWidth: '100%',
            }}
          >
            <iframe
              title={preview.title}
              srcDoc={preview.doc}
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
        ))}
      </div>
      <p className="mb-3 text-center text-xs text-gray-400">
        Shown at {previewScale.toFixed(1)}× — actual size {widthMm} × {heightMm} mm
        {useDaemon && daemon?.mediaDescription ? ` on ${daemon.mediaDescription}` : ''}
      </p>

      <label className="mb-1 flex items-start gap-2 text-sm text-gray-700">
        <input
          type="checkbox"
          checked={withText}
          onChange={(e) => setWithText(e.target.checked)}
          className="mt-0.5"
        />
        <span>Print the part label (part number and description).</span>
      </label>
      <label className="mb-1 flex items-start gap-2 text-sm text-gray-700">
        <input
          type="checkbox"
          checked={withBarcode}
          onChange={(e) => toggleBarcode(e.target.checked)}
          className="mt-0.5"
        />
        <span>
          Print a barcode label (<span className="font-mono">{code}</span>) — scan it to jump
          straight to this part.
        </span>
      </label>
      {barcodeTooNarrow && (
        <p className="mb-1 text-xs text-amber-600">
          This label is too narrow for a scannable barcode — only the code will be printed. Use a
          wider label for the bars.
        </p>
      )}
      {nothingSelected && (
        <p className="mb-1 text-xs text-amber-600">Pick at least one label to print.</p>
      )}
      <div className="mb-4" />

      {useDaemon ? (
        <>
          <p className="mb-4 text-xs text-gray-500">
            Prints silently through your paired daemon — no dialog will appear.
          </p>
          {daemonState === 'printing' && (
            <p className="mb-3 text-sm text-blue-600">
              Sending the {printingStep === 'barcode' ? 'barcode label' : 'label'} to the printer…
            </p>
          )}
          {daemonState === 'done' && !printingStep && (
            <p className="mb-3 text-sm text-green-600">Printed.</p>
          )}
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
            disabled={nothingSelected || daemonState === 'sending' || daemonState === 'printing'}
            className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-50"
          >
            {daemonState === 'sending' || daemonState === 'printing' ? 'Printing…' : 'Print'}
          </button>
        ) : (
          <button
            onClick={() => printLabel(printDoc)}
            disabled={nothingSelected}
            className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-50"
          >
            🏷️ Print
          </button>
        )}
      </div>
    </Modal>
  );
}
