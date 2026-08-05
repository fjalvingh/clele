import { useCallback, useEffect, useRef, useState } from 'react';

/**
 * Camera barcode scanning via the browser's native `BarcodeDetector`.
 *
 * Deliberately dependency-free: the platform API is used where it exists rather than shipping a
 * WASM/JS decoder. `isCameraScanSupported()` is the guard callers use to decide whether to offer
 * the button at all — see the note there about which browsers qualify.
 */

interface DetectedBarcode {
  rawValue: string;
  format: string;
}

interface BarcodeDetectorLike {
  detect(source: CanvasImageSource): Promise<DetectedBarcode[]>;
}

declare global {
  interface Window {
    BarcodeDetector?: {
      new (options?: { formats?: string[] }): BarcodeDetectorLike;
      getSupportedFormats(): Promise<string[]>;
    };
  }

  // `torch` is implemented (Android Chrome) but not in the DOM typings — declared here so the
  // capability check and the constraint below are both plain typed property access, no casts.
  interface MediaTrackCapabilities {
    torch?: boolean;
  }
  interface MediaTrackConstraintSet {
    torch?: boolean;
  }
}

/**
 * Formats worth looking for, in rough order of how often they turn up on parts:
 * `code_128` is what this app prints on its own labels (CLE-000123), the EAN/UPC family covers
 * retail packaging, and `data_matrix` / `qr_code` cover distributor bags. Anything the browser
 * does not support is dropped before the detector is constructed — passing an unsupported format
 * makes the constructor throw.
 */
const WANTED_FORMATS = [
  'code_128',
  'code_39',
  'ean_13',
  'ean_8',
  'upc_a',
  'upc_e',
  'itf',
  'data_matrix',
  'qr_code',
];

/**
 * Whether the camera path can work at all in this browser.
 *
 * Two independent requirements, and both fail quietly in ways worth distinguishing:
 * `BarcodeDetector` is missing on Firefox and on iOS Safari; `navigator.mediaDevices` is undefined
 * outside a secure context, which is the common case when this app is self-hosted on a plain
 * `http://192.168.x.x` LAN address. `cameraScanUnavailableReason()` tells the two apart for the UI.
 */
export function isCameraScanSupported(): boolean {
  return typeof window !== 'undefined'
    && 'BarcodeDetector' in window
    && !!navigator.mediaDevices?.getUserMedia;
}

export function cameraScanUnavailableReason(): string | null {
  if (isCameraScanSupported()) return null;
  if (!navigator.mediaDevices?.getUserMedia) {
    return window.isSecureContext
      ? 'This browser does not allow camera access.'
      : 'Camera scanning needs a secure connection — open this app over HTTPS (or on localhost).';
  }
  return 'This browser has no built-in barcode reader. Chrome or Edge on Android and desktop support it; Firefox and iOS Safari do not.';
}

interface CameraScannerProps {
  open: boolean;
  onClose: () => void;
  /** Called with the decoded barcode. The scanner closes itself first. */
  onDetected: (code: string) => void;
}

export default function CameraScanner({ open, onClose, onDetected }: CameraScannerProps) {
  const videoRef = useRef<HTMLVideoElement>(null);
  const streamRef = useRef<MediaStream | null>(null);
  const timerRef = useRef<number | null>(null);
  // Guards against a detection firing while we are already tearing down.
  const doneRef = useRef(false);

  const [error, setError] = useState<string | null>(null);
  const [starting, setStarting] = useState(true);
  const [torchOn, setTorchOn] = useState(false);
  const [torchAvailable, setTorchAvailable] = useState(false);

  const stop = useCallback(() => {
    if (timerRef.current !== null) {
      window.clearInterval(timerRef.current);
      timerRef.current = null;
    }
    streamRef.current?.getTracks().forEach((t) => t.stop());
    streamRef.current = null;
  }, []);

  const close = useCallback(() => {
    stop();
    onClose();
  }, [stop, onClose]);

  useEffect(() => {
    if (!open) return;

    doneRef.current = false;
    setError(null);
    setStarting(true);
    setTorchOn(false);
    setTorchAvailable(false);

    let cancelled = false;

    (async () => {
      try {
        const supported = await window.BarcodeDetector!.getSupportedFormats();
        const formats = WANTED_FORMATS.filter((f) => supported.includes(f));
        if (formats.length === 0) {
          throw new Error('This browser’s barcode reader supports none of the formats used on parts.');
        }
        const detector = new window.BarcodeDetector!({ formats });

        // `ideal` rather than `exact` so a laptop with only a front camera still works.
        const stream = await navigator.mediaDevices.getUserMedia({
          video: { facingMode: { ideal: 'environment' } },
        });
        if (cancelled) {
          stream.getTracks().forEach((t) => t.stop());
          return;
        }
        streamRef.current = stream;

        const video = videoRef.current;
        if (!video) return;
        video.srcObject = stream;
        await video.play();
        if (cancelled) return;
        setStarting(false);

        const track = stream.getVideoTracks()[0];
        // Torch is a real help reading a tiny SMD reel label on a dim bench, but it is only
        // exposed on some Android devices — offer it only where the track admits to having it.
        if (track?.getCapabilities?.().torch) setTorchAvailable(true);

        // 150ms is comfortably faster than a human can re-aim, and much cheaper than decoding
        // every animation frame.
        timerRef.current = window.setInterval(async () => {
          if (doneRef.current || !videoRef.current || videoRef.current.readyState < 2) return;
          try {
            const found = await detector.detect(videoRef.current);
            const code = found.find((b) => b.rawValue.trim())?.rawValue.trim();
            if (code && !doneRef.current) {
              doneRef.current = true;
              stop();
              onDetected(code);
            }
          } catch {
            // A single failed frame is normal (motion blur, partial code) — keep polling.
          }
        }, 150);
      } catch (e) {
        if (cancelled) return;
        const err = e as Error;
        setStarting(false);
        setError(
          err.name === 'NotAllowedError'
            ? 'Camera permission was denied. Allow camera access for this site, then try again.'
            : err.name === 'NotFoundError'
            ? 'No camera was found on this device.'
            : err.message || 'Could not start the camera.'
        );
      }
    })();

    return () => {
      cancelled = true;
      stop();
    };
  }, [open, stop, onDetected]);

  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') close();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [open, close]);

  const toggleTorch = async () => {
    const track = streamRef.current?.getVideoTracks()[0];
    if (!track) return;
    const next = !torchOn;
    try {
      await track.applyConstraints({ advanced: [{ torch: next }] });
      setTorchOn(next);
    } catch {
      setTorchAvailable(false);
    }
  };

  if (!open) return null;

  return (
    <div className="fixed inset-0 z-50 flex flex-col bg-black">
      {/* Controls sit above the video, clear of the notch on a phone */}
      <div className="flex shrink-0 items-center justify-between px-3 pb-2 pt-[max(0.75rem,env(safe-area-inset-top))] text-white">
        <span className="text-sm font-medium">Scan a barcode</span>
        <div className="flex items-center gap-1">
          {torchAvailable && (
            <button
              type="button"
              onClick={toggleTorch}
              aria-label={torchOn ? 'Turn light off' : 'Turn light on'}
              aria-pressed={torchOn}
              className={`rounded-lg p-2.5 transition-colors ${
                torchOn ? 'bg-white/20 text-white' : 'text-neutral-300 hover:bg-white/10'
              }`}
            >
              <svg
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                strokeWidth={1.7}
                strokeLinecap="round"
                strokeLinejoin="round"
                className="h-5 w-5"
              >
                <path d="M9 2h6l-.5 4h-5L9 2ZM9.5 6h5l.5 3-1 2v11h-4V11l-1-2Z" />
              </svg>
            </button>
          )}
          <button
            type="button"
            onClick={close}
            aria-label="Close scanner"
            className="rounded-lg p-2.5 text-neutral-300 transition-colors hover:bg-white/10 hover:text-white"
          >
            <svg
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              strokeWidth={1.7}
              strokeLinecap="round"
              strokeLinejoin="round"
              className="h-6 w-6"
            >
              <path d="M6 6l12 12M18 6 6 18" />
            </svg>
          </button>
        </div>
      </div>

      <div className="relative min-h-0 flex-1">
        <video
          ref={videoRef}
          className="h-full w-full object-cover"
          playsInline
          muted
          autoPlay
        />

        {/* Aiming frame — purely visual; detection runs on the whole frame. */}
        {!error && !starting && (
          <div className="pointer-events-none absolute inset-0 flex items-center justify-center">
            <div className="h-36 w-11/12 max-w-sm rounded-xl border-2 border-white/70 shadow-[0_0_0_100vmax_rgba(0,0,0,0.35)]" />
          </div>
        )}

        {starting && !error && (
          <div className="absolute inset-0 flex items-center justify-center">
            <p className="text-sm text-neutral-300">Starting camera…</p>
          </div>
        )}

        {error && (
          <div className="absolute inset-0 flex items-center justify-center p-6">
            <div className="max-w-sm rounded-xl bg-neutral-900 p-5 text-center">
              <p className="text-sm text-neutral-200">{error}</p>
              <button
                type="button"
                onClick={close}
                className="mt-4 rounded-md bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700"
              >
                Close
              </button>
            </div>
          </div>
        )}
      </div>

      {!error && (
        <p className="shrink-0 px-4 pb-[max(0.75rem,env(safe-area-inset-bottom))] pt-2 text-center text-xs text-neutral-400">
          Hold the code inside the frame
        </p>
      )}
    </div>
  );
}
