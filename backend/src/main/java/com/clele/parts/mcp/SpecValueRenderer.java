package com.clele.parts.mcp;

import com.clele.parts.model.SpecDefinition;
import com.clele.parts.model.UnitFamily;
import com.clele.parts.service.MetricUnitFormatter;

import java.math.BigDecimal;

/**
 * Renders a stored spec value into the form an engineer writes, for the benefit of a model reading
 * the catalogue. A numeric value is stored in its family's base SI unit, so {@code capacitance}
 * comes back as {@code 1E-7} — correct, comparable, and unreadable. This is the same job
 * {@code PartDetail.formatStoredNumber} does for the screen, and it uses the same formatter, so the
 * two cannot disagree about what a number means.
 *
 * <p>The raw value travels beside the rendered one in every tool result: the display string is for
 * reading, the raw number is what a follow-up parametric query compares against.
 */
final class SpecValueRenderer {

    private SpecValueRenderer() {}

    /** The value as it should be read: "100nF", "5 V (4.5 V ~ 5.5 V)", "≤ 16 V", or plain text. */
    static String display(Object raw, SpecDefinition definition) {
        if (raw == null) return "";
        UnitFamily family = definition == null ? null : definition.family().orElse(null);

        if (raw instanceof Number number) {
            return one(new BigDecimal(number.toString()), definition, family);
        }

        String text = String.valueOf(raw);
        if (!text.contains("..")) {
            return text;
        }
        // The wire form of a bound value: "min..max", or "min..nominal..max" once a value carries
        // both (V56). An open end is written "null".
        String[] parts = text.split("\\.\\.", -1);
        try {
            if (parts.length == 2) {
                return bounds(parts[0], parts[1], definition, family);
            }
            if (parts.length == 3) {
                String nominal = one(parse(parts[1]), definition, family);
                String range = bounds(parts[0], parts[2], definition, family);
                return range.isEmpty() ? nominal : nominal + " (" + range + ")";
            }
        } catch (NumberFormatException e) {
            // Not a bound value after all — a text spec that happens to contain "..".
            return text;
        }
        return text;
    }

    /** "4.5 V ~ 5.5 V" when both ends are known, "≥ 4.5 V" / "≤ 5.5 V" when only one is. */
    private static String bounds(String rawMin, String rawMax, SpecDefinition definition,
                                 UnitFamily family) {
        BigDecimal min = parse(rawMin);
        BigDecimal max = parse(rawMax);
        if (min != null && max != null) {
            return one(min, definition, family) + " ~ " + one(max, definition, family);
        }
        if (min != null) return "≥ " + one(min, definition, family);
        if (max != null) return "≤ " + one(max, definition, family);
        return "";
    }

    private static String one(BigDecimal value, SpecDefinition definition, UnitFamily family) {
        if (value == null) return "";
        if (family != null) {
            return MetricUnitFormatter.format(value, family);
        }
        String plain = value.stripTrailingZeros().toPlainString();
        String unit = definition == null ? null : definition.getUnit();
        return (unit == null || unit.isBlank()) ? plain : plain + " " + unit.trim();
    }

    private static BigDecimal parse(String bound) {
        String trimmed = bound.trim();
        if (trimmed.isEmpty() || "null".equals(trimmed)) return null;
        return new BigDecimal(trimmed);
    }
}
