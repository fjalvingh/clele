package com.clele.parts.service;

import com.clele.parts.model.UnitFamily;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the value of a NUMBER spec field: a nominal, a band, or both — or nothing at all.
 *
 * <h2>Why "or nothing at all"</h2>
 *
 * A NUMBER field's value belongs in {@code value_num}/{@code value_min}/{@code value_max}, where
 * search can reach it. A value that cannot be read as a number and is parked in {@code value_text}
 * instead is a value that only <em>displays</em>: "supply voltage between 3 and 5 V" will not find
 * the part whose supply voltage reads "5V ± 10%", and the field looks populated while being
 * invisible to every query the field exists for. So a refusal here means the value is dropped, and
 * this parser's job is to make refusals rare enough that dropping is the right answer — it accepts
 * the spellings people and machines actually write, and only those.
 *
 * <h2>The ladder</h2>
 *
 * <ol>
 *   <li><b>Cleanup</b> — Unicode spaces folded, a trailing parenthesised note ("(6V)", "(no-load)")
 *       and trailing qualifiers ("DC", "AC", "RMS", "typ", "nom") removed, unit words folded to
 *       their symbols ("ohms" → Ω, "microseconds" → µs, "degrees" → °).</li>
 *   <li><b>Tolerance</b> — {@code "5 V ± 10%"}, {@code "180° ± 3°"} → nominal with both bounds.</li>
 *   <li><b>Range</b> — {@code "3..16"}, {@code "min..nom..max"}, {@code "-40 °C ~ 105 °C"},
 *       {@code "15 V to 35 V"}, {@code "4.8-6.0 V"}, {@code "-15–70 °C"}.</li>
 *   <li><b>One-sided</b> — {@code "> 600 Hz"}, {@code "≤ 5 V"}, {@code "up to 50 W"},
 *       {@code "5 V max"} → a single bound, the other open.</li>
 *   <li><b>Scalar</b> — through {@link MetricUnitParser}: the family's units and prefixes and RKM
 *       ({@code "4k7"}), or the field's declared unit, or a plain decimal when it declares
 *       neither.</li>
 * </ol>
 *
 * <p>⚠️ <b>A field with no unit family and no declared unit accepts only a plain number.</b>
 * {@code "16 mA"} in such a field is refused rather than read as 16: nothing says what its numbers
 * are counted in, so there is no scale to convert to and 16 could as easily mean 0.016.
 */
public final class NumericSpecParser {

    private NumericSpecParser() {}

    /**
     * One numeric value. Any of the three may be null; at least one is not.
     *
     * <p>There is no "range or scalar" distinction — a value with empty bounds <em>is</em> a scalar,
     * which is why the row holds all three columns and the check constraint only separates numbers
     * from text.
     */
    public record Parsed(BigDecimal num, BigDecimal min, BigDecimal max) {
        public boolean isEmpty() {
            return num == null && min == null && max == null;
        }
    }

    // ── Cleanup ───────────────────────────────────────────────────────────────────────────────

    /** A trailing note in brackets: "1.2 A (6V)", "220 mA (6V, no-load)". */
    private static final Pattern TRAILING_NOTE = Pattern.compile("\\s*\\([^()]*\\)\\s*$");

    /**
     * Trailing words that qualify a reading without changing it. "min"/"max" are deliberately NOT
     * here — they change which bound the number is, and are handled as one-sided markers.
     */
    private static final Pattern TRAILING_QUALIFIER = Pattern.compile(
            "(?i)[\\s,]*\\b(dc|ac|rms|pk|p-p|pp|typ|typical|nom|nominal|approx|approximately)\\b\\.?\\s*$");

    /**
     * Unit words folded to their symbols, longest first so "microseconds" is not seen as "micro" +
     * "seconds" by a shorter rule. Only forms actually observed in the catalogue and in AI/datasheet
     * output are here — this is a fold list, not a units library.
     */
    private static final String[][] UNIT_WORDS = {
            {"(?i)\\bmicroseconds?\\b", "µs"}, {"(?i)\\b[uµ]sec\\b", "µs"},
            {"(?i)\\bmilliseconds?\\b", "ms"}, {"(?i)\\bmsec\\b", "ms"},
            {"(?i)\\bnanoseconds?\\b", "ns"}, {"(?i)\\bnsec\\b", "ns"},
            {"(?i)\\bpicoseconds?\\b", "ps"},
            {"(?i)\\bseconds?\\b", "s"}, {"(?i)\\bsecs?\\b", "s"},
            {"(?i)\\bkilohertz\\b", "kHz"}, {"(?i)\\bmegahertz\\b", "MHz"}, {"(?i)\\bhertz\\b", "Hz"},
            {"(?i)\\bmilliamp(ere)?s?\\b", "mA"}, {"(?i)\\bmicroamp(ere)?s?\\b", "µA"},
            {"(?i)\\bamp(ere)?s?\\b", "A"},
            // "9-40 VDC": the qualifier is glued to the symbol, so no word boundary separates them
            // and the trailing-qualifier rule cannot see it.
            {"(?i)\\bV(?:DC|AC)\\b", "V"},
            {"(?i)\\bmillivolts?\\b", "mV"}, {"(?i)\\bkilovolts?\\b", "kV"}, {"(?i)\\bvolts?\\b", "V"},
            {"(?i)\\bmilliwatts?\\b", "mW"}, {"(?i)\\bkilowatts?\\b", "kW"}, {"(?i)\\bwatts?\\b", "W"},
            {"(?i)\\bkilo-?ohms?\\b", "kΩ"}, {"(?i)\\bmilliohms?\\b", "mΩ"}, {"(?i)\\bohms?\\b", "Ω"},
            {"(?i)\\bfarads?\\b", "F"}, {"(?i)\\bhenr(y|ies)\\b", "H"},
            {"(?i)\\bdegrees?\\b", "°"}, {"(?i)\\bdeg\\b", "°"},
    };

    /** Everything that happens to a value before any shape is looked for. */
    static String clean(String raw) {
        if (raw == null) return "";
        String s = MetricUnitParser.normalizeSpaces(raw);
        for (String[] rule : UNIT_WORDS) s = s.replaceAll(rule[0], rule[1]);
        // Repeated: "0.16 sec/60° (4.8V) typ" sheds one layer at a time.
        String previous;
        do {
            previous = s;
            s = TRAILING_NOTE.matcher(s).replaceAll("");
            s = TRAILING_QUALIFIER.matcher(s).replaceAll("");
            s = s.strip();
        } while (!s.equals(previous));
        return s;
    }

    // ── Shapes ────────────────────────────────────────────────────────────────────────────────

    /** "5 V ± 10%", "180° ±3°" — a nominal and how far either way it may sit. */
    private static final Pattern TOLERANCE =
            Pattern.compile("^(.*?)\\s*(?:±|\\+/-|\\+-)\\s*(.+)$");

    /** Leading comparison markers, and the trailing "max"/"min" that mean the same thing. */
    private static final Pattern LEADING_MAX =
            Pattern.compile("(?i)^(?:<=|<|≤|max\\.?|maximum|up\\s+to|no\\s+more\\s+than)\\s*(.+)$");
    private static final Pattern LEADING_MIN =
            Pattern.compile("(?i)^(?:>=|>|≥|min\\.?|minimum|at\\s+least|from)\\s*(.+)$");
    private static final Pattern TRAILING_MAX = Pattern.compile("(?i)^(.+?)\\s+(?:max\\.?|maximum)$");
    private static final Pattern TRAILING_MIN = Pattern.compile("(?i)^(.+?)\\s+(?:min\\.?|minimum)$");

    /**
     * Range separators, in the order they are tried. The dotted form may carry three components
     * (min..nominal..max); the rest are always two.
     *
     * <p>⚠️ A plain hyphen is the dangerous one — "-40-125" cannot be told from a negative number —
     * so it is only tried when the value does not open with a sign, and, like the en dash, only
     * accepted when <em>both</em> sides parse. That is what keeps "1e-7" a single number.
     */
    private static final Pattern EN_DASH = Pattern.compile("\\s*[–—]\\s*");
    private static final Pattern HYPHEN = Pattern.compile("(?<=[0-9%°ΩA-Za-z])\\s*-\\s*(?=[0-9.])");

    /**
     * Read {@code raw} against a field that measures {@code family} (may be null) and declares
     * {@code unit} (may be null/blank/multi-unit). Empty when nothing realistic could be read.
     */
    public static Optional<Parsed> parse(String raw, UnitFamily family, String unit) {
        String s = clean(raw);
        if (s.isEmpty()) return Optional.empty();

        // 1 ── the Partsbox/UI dotted form, and the two written range spellings.
        String[] dotted = shareUnit(s.split("\\.\\.", 3));
        if (dotted.length == 3) return writtenRange(dotted[0], dotted[1], dotted[2], family, unit);
        if (dotted.length == 2) return writtenRange(dotted[0], null, dotted[1], family, unit);
        for (Pattern sep : new Pattern[]{Pattern.compile("~"), Pattern.compile("(?i)\\s+to\\s+")}) {
            String[] parts = sep.split(s, 2);
            if (parts.length == 2) {
                parts = shareUnit(parts);
                return writtenRange(parts[0], null, parts[1], family, unit);
            }
        }

        // 2 ── tolerance: a nominal with a band derived from it.
        Matcher tol = TOLERANCE.matcher(s);
        if (tol.matches()) return tolerance(tol.group(1), tol.group(2), family, unit);

        // 3 ── one-sided.
        Optional<Parsed> bound = oneSided(s, family, unit);
        if (bound.isPresent()) return bound;

        // 4 ── a strict range: both sides must parse, or this was never a range.
        Optional<Parsed> strict = strictRange(EN_DASH, s, family, unit);
        if (strict.isPresent()) return strict;
        if (!s.startsWith("-") && !s.startsWith("+")) {
            strict = strictRange(HYPHEN, s, family, unit);
            if (strict.isPresent()) return strict;
        }

        // 5 ── a plain value.
        return scalar(s, family, unit).map(n -> new Parsed(n, null, null));
    }

    /**
     * The dotted / tilde / "to" forms, where the separator has already declared this a range: an
     * unreadable component is an open bound ("4.5..null"), not a reason to refuse the whole value.
     */
    private static Optional<Parsed> writtenRange(String min, String nominal, String max,
                                                 UnitFamily family, String unit) {
        Parsed p = new Parsed(bound(nominal, family, unit),
                              bound(min, family, unit),
                              bound(max, family, unit));
        return p.isEmpty() ? Optional.empty() : Optional.of(p);
    }

    /** One component of a written range. "null" and anything unreadable mean "open". */
    private static BigDecimal bound(String raw, UnitFamily family, String unit) {
        if (raw == null) return null;
        String s = clean(raw);
        if (s.isEmpty() || s.equalsIgnoreCase("null")) return null;
        return scalar(s, family, unit).orElse(null);
    }

    /** "4.8-6.0 V": accepted only when both halves read as numbers. */
    private static Optional<Parsed> strictRange(Pattern sep, String s, UnitFamily family, String unit) {
        String[] parts = sep.split(s, 2);
        if (parts.length != 2) return Optional.empty();
        parts = shareUnit(parts);
        Optional<BigDecimal> lo = scalar(parts[0], family, unit);
        Optional<BigDecimal> hi = scalar(parts[1], family, unit);
        if (lo.isEmpty() || hi.isEmpty()) return Optional.empty();
        return Optional.of(new Parsed(null, lo.get(), hi.get()));
    }

    /** The leading number of a component, and everything after it — ("500", " µs"). */
    private static final Pattern LEADING_NUMBER =
            Pattern.compile("^([-+]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][-+]?\\d+)?)(.*)$");

    /**
     * Lend the unit one side of a range writes to the sides that omit it.
     *
     * <p>⚠️ Without this, "500-2500 µs" reads as 500 <b>seconds</b> to 2.5 ms — a bare number means
     * the base unit, and the bound that carries the prefix is nine orders of magnitude away from the
     * one that does not. People write the unit once, at the end, and mean it for both.
     */
    private static String[] shareUnit(String[] components) {
        String shared = "";
        for (String c : components) {
            Matcher m = LEADING_NUMBER.matcher(c.strip());
            if (m.matches() && !m.group(2).isBlank()) {
                shared = m.group(2).strip();
                break;
            }
        }
        if (shared.isEmpty()) return components;
        String[] out = new String[components.length];
        for (int i = 0; i < components.length; i++) {
            String c = components[i].strip();
            Matcher m = LEADING_NUMBER.matcher(c);
            out[i] = m.matches() && m.group(2).isBlank() ? c + " " + shared : components[i];
        }
        return out;
    }

    /** "5 V ± 10%" and "5 V ± 0.2 V" — the second form must be in the field's own unit. */
    private static Optional<Parsed> tolerance(String nominal, String spread,
                                              UnitFamily family, String unit) {
        Optional<BigDecimal> nom = scalar(nominal, family, unit);
        if (nom.isEmpty()) return Optional.empty();
        String spreadText = clean(spread);
        BigDecimal delta;
        if (spreadText.endsWith("%")) {
            Optional<BigDecimal> pct = plain(spreadText.substring(0, spreadText.length() - 1));
            if (pct.isEmpty()) return Optional.empty();
            delta = nom.get().abs().multiply(pct.get()).divide(new BigDecimal("100"), MathContext.DECIMAL64);
        } else {
            Optional<BigDecimal> abs = scalar(spreadText, family, unit);
            if (abs.isEmpty()) return Optional.empty();
            delta = abs.get().abs();
        }
        return Optional.of(new Parsed(nom.get(), nom.get().subtract(delta), nom.get().add(delta)));
    }

    private static Optional<Parsed> oneSided(String s, UnitFamily family, String unit) {
        Matcher m = LEADING_MAX.matcher(s);
        if (m.matches()) return scalar(m.group(1), family, unit).map(v -> new Parsed(null, null, v));
        m = LEADING_MIN.matcher(s);
        if (m.matches()) return scalar(m.group(1), family, unit).map(v -> new Parsed(null, v, null));
        m = TRAILING_MAX.matcher(s);
        if (m.matches()) return scalar(m.group(1), family, unit).map(v -> new Parsed(null, null, v));
        m = TRAILING_MIN.matcher(s);
        if (m.matches()) return scalar(m.group(1), family, unit).map(v -> new Parsed(null, v, null));
        return Optional.empty();
    }

    /**
     * One number, in the field's base unit.
     *
     * <p>The family is asked first because it knows more (its prefix window, RKM, its base marker).
     * A field with only a declared {@code unit} — the older convert-to-number shape — is parsed
     * against that. A field with neither takes a plain decimal and nothing else.
     */
    private static Optional<BigDecimal> scalar(String raw, UnitFamily family, String unit) {
        String s = clean(raw);
        if (s.isEmpty()) return Optional.empty();
        if (family != null) {
            return MetricUnitParser.parseToBase(s, family).map(BigDecimal::new);
        }
        if (unit != null && !unit.isBlank() && !unit.contains(",")) {
            return MetricUnitParser.parseToBase(s, unit.trim()).map(BigDecimal::new);
        }
        return plain(s);
    }

    private static Optional<BigDecimal> plain(String s) {
        try {
            return Optional.of(new BigDecimal(MetricUnitParser.normalizeSpaces(s)));
        } catch (NumberFormatException | NullPointerException e) {
            return Optional.empty();
        }
    }
}
