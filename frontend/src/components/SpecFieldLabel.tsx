import type { SpecDefinition } from '../api/types';
import { unitFamily } from '../utils/units';

/**
 * The label of one spec input: a type icon, the spec's name, and the unit its value is stored in.
 *
 * Which control a spec gets is decided per data type in four places (the part edit modal, the Parts
 * create modal, Quick Add, and the kit templates), and by the time a field is on screen nothing says
 * what it will accept — a NUMBER and a TEXT field look identical until a value is rejected, and a
 * numeric field's value is stored in a base SI unit the field itself never names. Both facts belong
 * in the label, which is the one part every branch renders.
 */

/** What the type icon means, in words — the tooltip and the accessible name. */
function typeName(dataType: string): string {
  switch (dataType) {
    case 'NUMBER': return 'Number';
    case 'BOOLEAN': return 'Yes / no';
    case 'SELECT': return 'Choice';
    case 'RANGE': return 'Range';
    case 'TEXT': return 'Text';
    default: return dataType || 'Text';
  }
}

/**
 * The unit a value of this spec is *stored* in, or null when the field names its own.
 *
 * A multi-unit spec ("KB, MB") carries a unit dropdown beside the input and the chosen unit is part
 * of the stored string, so there is no single unit to advertise. Everything else either declares one
 * outright or inherits its family's base SI unit — which is the one a metric-prefix field's number
 * is held in whatever prefix the dropdown happens to show.
 */
export function specBaseUnit(spec: SpecDefinition): string | null {
  const units = spec.unit ? spec.unit.split(',').map((s) => s.trim()).filter(Boolean) : [];
  if (units.length > 1) return null;
  if (units.length === 1) return units[0];
  return unitFamily(spec.unitFamily)?.baseUnit || null;
}

// Inline SVG rather than a glyph (#, ✓, ▾): a Unicode symbol renders as an empty box wherever the
// platform font lacks it, and `currentColor` keeps the icon in step with the label in both themes.
function iconPaths(dataType: string) {
  switch (dataType) {
    case 'NUMBER': // hash
      return <path d="M4 9h16M4 15h16M10 3 8 21M16 3l-2 18" />;
    case 'BOOLEAN': // ticked box
      return (
        <>
          <rect x="3" y="3" width="18" height="18" rx="2" />
          <path d="m8 12 3 3 5-6" />
        </>
      );
    case 'SELECT': // list with a chevron
      return (
        <>
          <path d="M4 7h10M4 12h10M4 17h6" />
          <path d="m15 14 3 3 3-3" />
        </>
      );
    case 'RANGE': // span between two bounds
      return <path d="M4 6v12M20 6v12M7 12h10M10 9l-3 3 3 3M14 9l3 3-3 3" />;
    default: // text — the same fallback the field renderers use for an unknown type
      return <path d="M4 7V4h16v3M9 20h6M12 4v16" />;
  }
}

export function SpecTypeIcon({ dataType, className }: { dataType: string; className?: string }) {
  const name = typeName(dataType);
  return (
    <svg
      viewBox="0 0 24 24"
      className={className ?? 'h-3.5 w-3.5 shrink-0 text-gray-400'}
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      strokeLinejoin="round"
      role="img"
      aria-label={name}
    >
      <title>{name}</title>
      {iconPaths(dataType)}
    </svg>
  );
}

/**
 * Drop-in replacement for the bare `{spec.name}` inside a field's own `<label>`: the surrounding
 * label keeps its classes, so the icon and the unit chip inherit the label's size and weight.
 */
export default function SpecFieldLabel({ spec }: { spec: SpecDefinition }) {
  const unit = specBaseUnit(spec);
  return (
    <span className="inline-flex items-center gap-1.5 align-middle">
      <SpecTypeIcon dataType={spec.dataType} />
      <span>{spec.name}</span>
      {unit && (
        <span
          title={`Stored in ${unit}`}
          className="rounded bg-gray-100 px-1.5 py-px font-mono text-[11px] font-normal leading-4 text-gray-500"
        >
          {unit}
        </span>
      )}
    </span>
  );
}
