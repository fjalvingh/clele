import { type Location } from '../api/types';

/**
 * "3 of 12 slots free" / "all 12 slots used" / "2 more parts than slots" — or null for a location
 * not divided into slots.
 */
export function slotSummary(loc: Location): string | null {
  if (loc.slotCount == null) return null;
  const free = loc.freeSlots ?? loc.slotCount;
  if (free > 0) return `${free} of ${loc.slotCount} slots free`;
  return free === 0 ? `all ${loc.slotCount} slots used` : `${-free} more parts than slots`;
}

/**
 * Option text for a picker where stock is put away: the breadcrumb, plus how many slots are
 * still free when the location is a slotted drawer.
 */
export function locationOptionLabel(loc: Location): string {
  const label = loc.breadcrumb || loc.name;
  const slots = slotSummary(loc);
  return slots ? `${label} (${slots})` : label;
}
