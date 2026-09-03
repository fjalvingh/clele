/**
 * When this browser last saw the signed-in account active.
 *
 * The session outlives a long absence — you can close the laptop for a week and come back to the
 * app still signed in — so the login page never gets a chance to mark a return. We track activity
 * here instead: one timestamp per account in localStorage, refreshed as the user works, and read
 * on the way in to decide whether they have been away long enough to be greeted.
 */

const KEY_PREFIX = 'sortiment.lastSeen.';

/** How long away counts as "been away". Anything shorter is a reload or a lunch break. */
export const AWAY_THRESHOLD_MS = 12 * 60 * 60 * 1000;

function key(userId: number): string {
  return `${KEY_PREFIX}${userId}`;
}

/**
 * The last recorded activity for this account, or null when there is none — a browser that has
 * never carried this account (or has had its storage cleared) is a first visit, not a return.
 * Storage can also be unavailable (private mode, blocked cookies); that reads as null too, so the
 * greeting simply never fires rather than the app breaking.
 */
export function readLastSeen(userId: number): number | null {
  try {
    const raw = localStorage.getItem(key(userId));
    if (!raw) return null;
    const ts = Number(raw);
    return Number.isFinite(ts) && ts > 0 ? ts : null;
  } catch {
    return null;
  }
}

/** Record that the account is active right now. */
export function markSeen(userId: number): void {
  try {
    localStorage.setItem(key(userId), String(Date.now()));
  } catch {
    /* storage unavailable — losing the timestamp only costs a greeting */
  }
}
