package com.clele.parts.service;

import com.clele.parts.model.UnitFamily;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Renders a value held in a family's base SI unit back into the form people write — the exact
 * inverse of {@link MetricUnitParser}, and the reason nothing needs a stored {@code display}
 * column: the rendering is computed from the value every time, so it cannot drift from it.
 *
 * <p>Mirrors {@code frontend/src/utils/units.ts}, which does the same job for edit fields. The two
 * must stay in step; {@code MetricUnitFormatterTest} pins the shared example table.
 *
 * <h2>Two styles</h2>
 *
 * <ul>
 *   <li><b>RKM</b> for resistance, capacitance and inductance — {@code 4k7}, {@code 100R},
 *       {@code 2n2}. The letter replaces the decimal point, which is how these values are marked on
 *       the component and written on the schematic.</li>
 *   <li><b>Ordinary</b> for everything else — {@code 9 mA}, {@code 1.5 ns}, {@code 3.3 V}.</li>
 * </ul>
 *
 * <h2>The window binds the renderer</h2>
 *
 * A prefix outside {@link UnitFamily#allowsExponent(int)} is never produced, because the parser is
 * required to refuse reading it back. {@code draintosourceresistance} really does hold {@code 0.0087};
 * an unrestricted engineering renderer would print {@code "8m7"}, which resistance refuses — the
 * round trip would be broken by the very rule meant to protect it. Outside the window the value keeps
 * its decimal point and takes the base marker as a suffix: {@code "0.0087R"}, which parses back
 * exactly.
 */
public final class MetricUnitFormatter {

    private MetricUnitFormatter() {}

    /** Exponent -> symbol, the rendering half of {@code MetricUnitParser.PREFIX_EXP}. */
    private static final Map<Integer, String> SYMBOL = new LinkedHashMap<>();

    static {
        SYMBOL.put(12, "T");
        SYMBOL.put(9, "G");
        SYMBOL.put(6, "M");
        SYMBOL.put(3, "k");
        SYMBOL.put(0, "");
        SYMBOL.put(-3, "m");
        SYMBOL.put(-6, "µ");
        SYMBOL.put(-9, "n");
        SYMBOL.put(-12, "p");
    }

    private static final int MAX_EXP = 12;
    private static final int MIN_EXP = -12;

    /**
     * Render {@code base} — a value in {@code family}'s base unit — as a display string. A null
     * value or a null family renders as the plain number (or the empty string), since a value with
     * no family was never parsed and has no unit to restore.
     */
    public static String format(BigDecimal base, UnitFamily family) {
        if (base == null) return "";
        if (family == null) return plain(base);

        int exp = engineeringExponent(base);
        boolean clamped = !family.allowsExponent(exp);
        if (clamped) exp = clampToWindow(exp, family);

        BigDecimal mantissa = base.scaleByPowerOfTen(-exp).stripTrailingZeros();
        String prefix = SYMBOL.getOrDefault(exp, "");

        // RKM carries no unit symbol — the letter stands for it, and at the base position it *is*
        // the marker ("100R"). Everywhere else the unit is written out, so the prefix stays a prefix
        // and the base position takes none at all ("3.3 V", not "3.3 VV").
        if (family.isRkm()) return rkm(plain(mantissa), exp == 0 ? family.getBaseMarker() : prefix, clamped);

        String unit = family.getBaseUnit();
        if (unit.isEmpty() && prefix.isEmpty()) return plain(mantissa);
        return plain(mantissa) + " " + prefix + unit;
    }

    /**
     * RKM form. The letter goes where the decimal point is ({@code 4.7} + {@code k} -> {@code 4k7});
     * with no fractional part it goes at the end ({@code 47k}, {@code 100R}).
     *
     * <p>When the value fell outside the family's prefix window the mantissa is not in [1, 1000) and
     * infixing would produce something unreadable ({@code 0R0087}), so the decimal point stays and
     * the letter becomes a plain suffix: {@code 0.0087R}. Both forms parse back to the same number.
     */
    private static String rkm(String mantissa, String letter, boolean clamped) {
        int dot = mantissa.indexOf('.');
        if (dot < 0) return mantissa + letter;
        if (clamped) return mantissa + letter;
        return mantissa.substring(0, dot) + letter + mantissa.substring(dot + 1);
    }

    /** floor(log10(|v|) / 3) * 3, computed exactly on the BigDecimal rather than through a double. */
    private static int engineeringExponent(BigDecimal v) {
        if (v.signum() == 0) return 0;
        int adjusted = v.precision() - v.scale() - 1;   // floor(log10(|v|))
        int exp = Math.floorDiv(adjusted, 3) * 3;
        return Math.max(MIN_EXP, Math.min(MAX_EXP, exp));
    }

    private static int clampToWindow(int exp, UnitFamily family) {
        int step = exp > 0 ? -3 : 3;
        for (int e = exp; e >= MIN_EXP && e <= MAX_EXP; e += step) {
            if (family.allowsExponent(e)) return e;
        }
        return 0;
    }

    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }
}
