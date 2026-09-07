import { useCallback, useEffect, useRef, useState } from 'react';
import { useAuth } from '../auth/AuthContext';
import { AWAY_THRESHOLD_MS, markSeen, readLastSeen } from '../utils/lastSeen';
import { pickLoginPhoto } from '../utils/loginPhotos';

/** Don't touch storage on every keystroke or wheel tick — once every half minute is plenty. */
const WRITE_INTERVAL_MS = 30_000;

/** The events that mean "the user is here"; a tab left open on an empty desk fires none of them. */
const ACTIVITY_EVENTS = ['pointerdown', 'keydown', 'wheel'] as const;

/** Shape of the panel until a photo has been measured (and when there is no photo to measure). */
const DEFAULT_RATIO = 3 / 2;

/** Roughly what the greeting + button below the photo occupy; the photo gets the rest of the height. */
const CAPTION_HEIGHT = '9.5rem';

/**
 * Greets a user who returns after a long absence (see AWAY_THRESHOLD_MS) with one of the login
 * photos — the sign-in screen they would have seen had the session not been kept alive for weeks.
 *
 * Lives in the app shell rather than on a page: the return may land on any deep link, and the
 * greeting belongs to the visit, not to the route. Signing in explicitly never greets — the login
 * page has just shown its own photo, and it stamps the timestamp as it hands over.
 */
export default function WelcomeBack() {
  const { user } = useAuth();
  const userId = user?.id;
  const [photo, setPhoto] = useState<string | undefined>();
  // Width/height of the photo, so the panel can take its shape instead of framing it. Landscape
  // photos make a wide panel, portrait ones a tall and narrow panel; the fallback only ever shows
  // when there is no photo at all.
  const [ratio, setRatio] = useState(DEFAULT_RATIO);
  const [open, setOpen] = useState(false);
  // Drives the fade: the overlay mounts transparent and is flipped on a tick later, and fades back
  // out before it unmounts.
  const [shown, setShown] = useState(false);
  const lastWrite = useRef(0);

  const close = useCallback(() => {
    setShown(false);
    window.setTimeout(() => setOpen(false), 200);
  }, []);

  // Read the previous stamp before writing the new one: on the first call it still holds the time
  // of the last visit, which is exactly what decides the greeting. The throttle guards the storage
  // access as much as the write — this runs on every wheel tick.
  const touch = useCallback(() => {
    if (!userId) return;
    const now = Date.now();
    if (now - lastWrite.current < WRITE_INTERVAL_MS) return;
    lastWrite.current = now;
    const last = readLastSeen(userId);
    markSeen(userId);
    // No stored visit at all means this browser has never carried the account — a first visit, not
    // a return, so it passes quietly.
    if (last === null || now - last <= AWAY_THRESHOLD_MS) return;
    const url = pickLoginPhoto();
    if (!url) {
      setOpen(true);
      return;
    }
    // Measure before opening rather than after: the panel is sized from the aspect ratio, and a
    // panel that opens square and then jumps to its real shape looks broken. The photos are
    // bundled assets, so this is a cache hit in all but the first visit.
    const probe = new Image();
    probe.onload = () => {
      if (probe.naturalHeight > 0) setRatio(probe.naturalWidth / probe.naturalHeight);
      setPhoto(url);
      setOpen(true);
    };
    probe.onerror = () => setOpen(true);
    probe.src = url;
  }, [userId]);

  useEffect(() => {
    if (!userId) return;
    // The entry check runs on the next tick rather than in the effect body: opening the greeting
    // is a state change, and there is no reason to cascade a render through the shell's mount.
    const entry = window.setTimeout(touch, 0);
    const onActivity = () => touch();
    // Becoming visible counts as activity too, and catches the tab left open for days.
    const onVisibility = () => {
      if (document.visibilityState === 'visible') touch();
    };
    ACTIVITY_EVENTS.forEach((e) => window.addEventListener(e, onActivity, { passive: true }));
    document.addEventListener('visibilitychange', onVisibility);
    return () => {
      window.clearTimeout(entry);
      ACTIVITY_EVENTS.forEach((e) => window.removeEventListener(e, onActivity));
      document.removeEventListener('visibilitychange', onVisibility);
    };
  }, [userId, touch]);

  useEffect(() => {
    if (!open) return;
    const id = window.setTimeout(() => setShown(true), 20);
    const onKeyDown = () => close();
    document.addEventListener('keydown', onKeyDown);
    return () => {
      window.clearTimeout(id);
      document.removeEventListener('keydown', onKeyDown);
    };
  }, [open, close]);

  if (!open) return null;

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-label="Welcome back to Sortiment"
      onClick={close}
      className={`fixed inset-0 z-[60] flex items-center justify-center bg-black/60 p-4 backdrop-blur-sm transition-opacity duration-200 ${
        shown ? 'opacity-100' : 'opacity-0'
      }`}
    >
      <div
        // The panel is as wide as the photo can be while its full height still fits above the
        // caption: `ratio` turns the available height into a width, the floor keeps a very tall
        // portrait from becoming a sliver, and max-width takes over on a screen too narrow for it.
        style={
          photo
            ? {
                width: `max(20rem, calc((88vh - ${CAPTION_HEIGHT}) * ${ratio}))`,
                maxWidth: '100%',
              }
            : { width: '28rem', maxWidth: '100%' }
        }
        className={`overflow-hidden rounded-2xl bg-surface shadow-2xl ring-1 ring-black/5 transition duration-200 ease-out dark:ring-white/10 ${
          shown ? 'scale-100 opacity-100' : 'scale-95 opacity-0'
        }`}
      >
        {photo && (
          <img
            src={photo}
            alt=""
            style={{ aspectRatio: ratio }}
            className="w-full bg-gray-100 object-cover"
          />
        )}
        <div className="px-6 py-6 text-center">
          <h2 className="text-xl font-semibold tracking-tight text-gray-900">
            Welcome back to Sortiment
          </h2>
          <button
            type="button"
            onClick={close}
            className="mt-5 rounded-lg bg-blue-600 px-5 py-2 text-sm font-medium text-white hover:bg-blue-700"
          >
            Continue
          </button>
        </div>
      </div>
    </div>
  );
}
