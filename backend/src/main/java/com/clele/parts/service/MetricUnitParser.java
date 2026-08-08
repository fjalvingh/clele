package com.clele.parts.service;

import com.clele.parts.model.UnitFamily;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses a spec value string down to its value in a base SI unit — {@code "9 mA"} against base
 * {@code "A"} is {@code "0.009"}, {@code "4k7"} against {@code "Ω"} is {@code "4700"}.
 *
 * <p>Mirrors the metric-prefix table in the frontend {@code utils/units.ts} so display (TS) and
 * conversion (Java) agree; {@link MetricUnitFormatter} is the inverse on this side. Used by the
 * "convert TEXT spec to NUMBER" feature and by the spec-value write funnel.
 *
 * <h2>Accepted forms</h2>
 *
 * <table>
 *   <tr><th>form</th><th>example</th><th>note</th></tr>
 *   <tr><td>bare number</td><td>{@code 4700}</td><td>already in the base unit</td></tr>
 *   <tr><td>number + unit</td><td>{@code 4700 Ω}</td><td></td></tr>
 *   <tr><td>number + prefix + unit</td><td>{@code 4.7 kΩ}, {@code 100nF}</td><td></td></tr>
 *   <tr><td>number + bare prefix</td><td>{@code 47k}, {@code 100n}</td><td>RKM, unit implied</td></tr>
 *   <tr><td>number + letter + digits</td><td>{@code 4k7}, {@code 4R7}, {@code 2n2}</td>
 *       <td>RKM, letter <em>is</em> the decimal point</td></tr>
 * </table>
 *
 * <p>RKM code (IEC 60062) exists because the decimal point is the least reliable character in
 * electronics — it vanishes on a silkscreen, a photocopy and a badly kerned datasheet table — so a
 * letter is put in its place. It is how components are marked and how people type them.
 *
 * <h2>The family window</h2>
 *
 * The two-argument {@link #parseToBase(String, String)} knows only a base unit and applies no
 * window. {@link #parseToBase(String, UnitFamily)} additionally enforces
 * {@link UnitFamily#allowsExponent(int)} over the <em>bare-letter</em> forms, so that a resistance
 * refuses {@code 4m7} and a capacitance refuses {@code 4M7}: those differ from {@code 4M7} /
 * {@code 4m7} by nine orders of magnitude and one shift key, with no unit symbol present to make the
 * mistake visible. A refused letter is an ordinary parse failure — the value stays text.
 *
 * <p>⚠️ The window binds the bare letter <b>only</b>. {@code "15 mΩ"} parses: where the symbol is
 * written out the reader and the parser see the same thing, and refusing it would break machine
 * intake for no gain (the component cache and the datasheet extractor both emit that form, and
 * sub-ohm resistance is ordinary — RDS(on), ESR, contact resistance, DCR).
 *
 * <p>⚠️ <b>Every value goes through {@link #normalizeSpaces} first</b> — neither
 * {@code trim()} nor {@code strip()} is sufficient on real vendor text. See that method.
 */
public final class MetricUnitParser {

    private MetricUnitParser() {}

    /** Case-sensitive SI prefix symbol -> power-of-ten exponent, plus tolerant aliases (u, K). */
    private static final Map<String, Integer> PREFIX_EXP = new LinkedHashMap<>();

    static {
        PREFIX_EXP.put("T", 12);
        PREFIX_EXP.put("G", 9);
        PREFIX_EXP.put("M", 6);
        PREFIX_EXP.put("k", 3);
        PREFIX_EXP.put("K", 3);   // tolerant alias for kilo — also the usual RKM spelling ("4K7")
        PREFIX_EXP.put("m", -3);
        PREFIX_EXP.put("µ", -6);
        PREFIX_EXP.put("u", -6);  // tolerant alias for micro
        PREFIX_EXP.put("n", -9);
        PREFIX_EXP.put("p", -12);
    }

    // Leading signed number: integer/decimal with optional exponent.
    private static final Pattern NUMBER = Pattern.compile("^[-+]?(\\d+\\.?\\d*|\\.\\d+)([eE][-+]?\\d+)?");

    // Leading *plain integer* — the only mantissa an RKM value may carry, since the letter is the
    // decimal point ("4.7k7" is not a number, it is a typo).
    private static final Pattern PLAIN_INT = Pattern.compile("^[-+]?\\d+$");

    // RKM tail: the letter(s) standing in for the decimal point, then the fractional digits.
    private static final Pattern RKM_TAIL = Pattern.compile("^([^0-9.\\s]+)(\\d+)$");

    // Every Unicode space separator, plus the stray line separators. \p{Zs} covers U+00A0, U+2009
    // and U+202F, which is the point — see normalizeSpaces.
    private static final Pattern UNICODE_SPACE = Pattern.compile("[\\p{Zs}\\u0085\\u2028\\u2029]");

    /**
     * Fold every kind of Unicode space to a plain one and trim the ends — the first thing done to
     * any value, and shared with the spec-value write funnel so both see the same string.
     *
     * <p>⚠️ <b>Neither {@code trim()} nor {@code strip()} is enough on its own.</b> {@code trim}
     * only removes characters at or below U+0020, and {@code strip} defers to
     * {@link Character#isWhitespace}, which deliberately answers <b>false</b> for the non-breaking
     * spaces U+00A0 and U+202F. Vendor and AI text is full of all three: the catalogue's own
     * {@code "5.5 V"} uses a thin space (U+2009) between number and unit, and a non-breaking space
     * is the usual way a datasheet keeps "100 nF" from wrapping. Left in place they become part of
     * the unit tail, so the value matches no unit and silently stays text — which is exactly how it
     * was found, by measuring against the real catalogue rather than by reading the code.
     */
    public static String normalizeSpaces(String s) {
        return s == null ? null : UNICODE_SPACE.matcher(s).replaceAll(" ").strip();
    }

    /** One successful parse: the value in the base unit, and how the magnitude was expressed. */
    private record Match(BigDecimal base, int exp, boolean bareLetter) {}

    /**
     * Parse {@code raw} into a value expressed in {@code baseUnit}, as a plain decimal string
     * (e.g. {@code "0.009"}), or empty when it matches none of the accepted forms. No family window
     * is applied — every prefix is accepted.
     */
    public static Optional<String> parseToBase(String raw, String baseUnit) {
        if (baseUnit == null) return Optional.empty();
        String unit = normalizeSpaces(baseUnit);
        return match(raw, unit, unit).map(m -> plain(m.base()));
    }

    /**
     * Parse {@code raw} into a value expressed in {@code family}'s base unit, enforcing the family's
     * prefix window over the bare-letter forms and its base marker ({@code R} for resistance).
     * Empty when the string parses to nothing, or to a magnitude the family refuses to see written
     * that way.
     */
    public static Optional<String> parseToBase(String raw, UnitFamily family) {
        if (family == null) return Optional.empty();
        return match(raw, family.getBaseUnit(), family.getBaseMarker())
                .filter(m -> m.exp() == 0
                        || !(m.bareLetter() || family.isScaleFree())
                        || family.allowsExponent(m.exp()))
                .map(m -> plain(m.base()));
    }

    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }

    private static Optional<Match> match(String raw, String baseUnit, String baseMarker) {
        if (raw == null || baseUnit == null) return Optional.empty();
        String s = normalizeSpaces(raw);
        if (s.isEmpty()) return Optional.empty();

        // A half-open Partsbox range with no lower bound ("null..X") collapses to its single defined
        // value X. Other ranges ("X..Y", "X..null") are handled by the caller as real ranges.
        if (s.regionMatches(true, 0, "null..", 0, 6)) {
            s = s.substring(6).strip();
            if (s.isEmpty()) return Optional.empty();
        }

        Matcher m = NUMBER.matcher(s);
        if (!m.find()) return Optional.empty();

        String numText = m.group();
        String rest = s.substring(m.end()).strip();

        // --- RKM infix: "4k7", "4R7", "2n2" — the letter is the decimal point. -------------------
        Matcher rkm = RKM_TAIL.matcher(rest);
        if (rkm.matches() && PLAIN_INT.matcher(numText).matches()) {
            Integer exp = letterExponent(rkm.group(1), baseUnit, baseMarker);
            if (exp == null) return Optional.empty();
            String sign = numText.startsWith("-") ? "-" : "";
            String digits = numText.replaceFirst("^[-+]", "");
            BigDecimal mantissa = new BigDecimal(sign + digits + "." + rkm.group(2));
            return Optional.of(new Match(scale(mantissa, exp), exp, true));
        }

        BigDecimal num;
        try {
            num = new BigDecimal(numText);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }

        // --- Number with the unit written out: "", "Ω", "kΩ", "mA". -------------------------------
        Integer exp = matchPrefix(rest, baseUnit);
        if (exp != null) return Optional.of(new Match(scale(num, exp), exp, false));

        // --- Number with a bare letter: "47k", "100n", "100R". ------------------------------------
        exp = letterExponent(rest, baseUnit, baseMarker);
        if (exp != null) return Optional.of(new Match(scale(num, exp), exp, true));

        return Optional.empty();
    }

    private static BigDecimal scale(BigDecimal mantissa, int exp) {
        return mantissa.scaleByPowerOfTen(exp);
    }

    /**
     * Exponent for a letter standing alone (no unit symbol beside it): a single SI prefix character,
     * or the family's base marker / base unit symbol, which mean "no scaling".
     */
    private static Integer letterExponent(String letter, String baseUnit, String baseMarker) {
        if (letter == null || letter.isEmpty()) return null;
        if (letter.equals(baseMarker) || letter.equalsIgnoreCase(baseUnit)) return 0;
        if (letter.length() == 1) return PREFIX_EXP.get(letter);
        return null;
    }

    /**
     * Exponent for the unit tail after the number: 0 if empty (bare number) or exactly the base unit;
     * the prefix exponent if it is {@code <one prefix char> + baseUnit}; null if it matches neither.
     */
    private static Integer matchPrefix(String rest, String baseUnit) {
        if (rest.isEmpty()) return 0;
        if (!baseUnit.isEmpty() && rest.equalsIgnoreCase(baseUnit)) return 0;
        if (!baseUnit.isEmpty() && rest.length() == baseUnit.length() + 1) {
            String prefix = rest.substring(0, 1);
            if (rest.substring(1).equalsIgnoreCase(baseUnit)) {
                return PREFIX_EXP.get(prefix);
            }
        }
        return null;
    }

    /**
     * How many {@code baseUnit} make up one {@code unit} — {@code ("mm", "m")} is {@code 0.001},
     * {@code ("kΩ", "Ω")} is {@code 1000}, {@code ("V", "V")} is {@code 1}. Empty when the two do not
     * reconcile ({@code ("°C", "V")}), which is the useful half: it is the caller's signal that a
     * value in {@code baseUnit} must <em>not</em> be written into a field declaring {@code unit}.
     *
     * <p>The inverse of {@link #parseToBase}, and implemented through it so the prefix table and its
     * matching rules have exactly one definition.
     */
    public static Optional<Double> factorToBase(String unit, String baseUnit) {
        if (unit == null || unit.isBlank()) return Optional.empty();
        return parseToBase("1" + normalizeSpaces(unit), baseUnit).map(Double::parseDouble);
    }

    /**
     * Best-effort guess of the base unit for a set of values: strip the leading number and a single
     * leading prefix char, then return the most common remaining alphabetic token. Empty string if none.
     *
     * <p>RKM values ({@code 4k7}) contribute nothing — they name no unit, which is the point of the
     * notation — so they are skipped rather than mistaken for a unit called "k7".
     */
    public static String suggestUnit(Collection<String> values) {
        Map<String, Integer> tally = new LinkedHashMap<>();
        for (String raw : values) {
            if (raw == null) continue;
            String s = normalizeSpaces(raw);
            Matcher m = NUMBER.matcher(s);
            if (!m.find()) continue;
            String rest = s.substring(m.end()).strip();
            if (rest.isEmpty() || !rest.chars().allMatch(Character::isLetter)) continue;
            // Drop a single leading prefix char when it leaves a non-empty unit.
            if (rest.length() > 1 && PREFIX_EXP.containsKey(rest.substring(0, 1))) {
                rest = rest.substring(1);
            }
            if (!rest.isEmpty()) tally.merge(rest, 1, Integer::sum);
        }
        return tally.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("");
    }
}
