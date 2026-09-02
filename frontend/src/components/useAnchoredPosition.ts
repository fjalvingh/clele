import { useLayoutEffect, useRef, type RefObject } from 'react';

const GAP = 4;
const MARGIN = 8;
/** Below this much room under the anchor, look at flipping the list above it. */
const MIN_ROOM_BELOW = 160;

interface Options {
  /** How wide the list may grow past its anchor, for the right-edge clamp. */
  maxWidth?: number;
  /** Cap on the list's height; the room actually available may make it smaller. Default 256. */
  maxHeight?: number;
  /** Pin the list to the anchor's width, rather than only using it as a minimum. */
  matchWidth?: boolean;
}

/**
 * Places a dropdown that is rendered in a **portal** against the input it belongs to. Returns the
 * ref to put on the portal element; attach it and give that element `position: fixed`.
 *
 * Comboboxes in this app sit inside dialogs whose body is `overflow-y-auto`, and an absolutely
 * positioned list inside that box is clipped by it: typing produced a scrollbar and nothing else.
 * A portal into `document.body` escapes the scroll container, and then has to be positioned by
 * hand — under the anchor when there is room, flipped above it (bottom edge against the input)
 * when there is not, capped to the space actually available on that side so the list scrolls
 * internally rather than running off-screen, and clamped to the viewport's right edge.
 *
 * Positioning happens in a layout effect, before paint, and repeats while open on scroll
 * (captured, so a scrolling dialog body counts) and on resize.
 */
export function useAnchoredPosition<T extends HTMLElement>(
  anchor: RefObject<HTMLElement | null>,
  open: boolean,
  options: Options = {},
): RefObject<T | null> {
  const listRef = useRef<T | null>(null);
  const { maxWidth, maxHeight = 256, matchWidth = false } = options;

  useLayoutEffect(() => {
    if (!open) return;

    const place = () => {
      const anchorEl = anchor.current;
      const list = listRef.current;
      if (!anchorEl || !list) return;
      const rect = anchorEl.getBoundingClientRect();
      const below = window.innerHeight - rect.bottom - GAP - MARGIN;
      const above = rect.top - GAP - MARGIN;
      const flip = below < MIN_ROOM_BELOW && above > below;
      const width = Math.max(rect.width, maxWidth ?? 0);

      list.style.left = `${Math.max(MARGIN, Math.min(rect.left, window.innerWidth - MARGIN - width))}px`;
      if (flip) {
        list.style.top = '';
        list.style.bottom = `${window.innerHeight - rect.top + GAP}px`;
      } else {
        list.style.bottom = '';
        list.style.top = `${rect.bottom + GAP}px`;
      }
      list.style.minWidth = `${rect.width}px`;
      if (matchWidth) list.style.width = `${rect.width}px`;
      list.style.maxHeight = `${Math.max(96, Math.min(maxHeight, flip ? above : below))}px`;
      list.style.visibility = 'visible';
    };

    place();
    window.addEventListener('scroll', place, true);
    window.addEventListener('resize', place);
    return () => {
      window.removeEventListener('scroll', place, true);
      window.removeEventListener('resize', place);
    };
  }, [open, anchor, maxWidth, maxHeight, matchWidth]);

  return listRef;
}
