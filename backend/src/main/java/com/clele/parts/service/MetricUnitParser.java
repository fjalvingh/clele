package com.clele.parts.service;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses a spec value string like {@code "9 mA"}, {@code "3.3V"} or {@code "100nF"} down to its value
 * in a chosen base SI unit (e.g. {@code "9 mA"} with base {@code "A"} -> {@code "0.009"}). Mirrors the
 * metric-prefix table in the frontend {@code utils/units.ts} so display (TS) and conversion (Java)
 * agree. Used by the "convert TEXT spec to NUMBER" feature.
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
        PREFIX_EXP.put("K", 3);   // tolerant alias for kilo
        PREFIX_EXP.put("m", -3);
        PREFIX_EXP.put("µ", -6);
        PREFIX_EXP.put("u", -6);  // tolerant alias for micro
        PREFIX_EXP.put("n", -9);
        PREFIX_EXP.put("p", -12);
    }

    // Leading signed number: integer/decimal with optional exponent.
    private static final Pattern NUMBER = Pattern.compile("^[-+]?(\\d+\\.?\\d*|\\.\\d+)([eE][-+]?\\d+)?");

    /**
     * Parse {@code raw} into a value expressed in {@code baseUnit}. Returns the cleaned base value as a
     * plain decimal string (e.g. {@code "0.009"}), or empty if the string can't be parsed as
     * {@code <number>[<prefix>]<baseUnit>} (a bare number is treated as already being in the base unit).
     */
    public static Optional<String> parseToBase(String raw, String baseUnit) {
        if (raw == null || baseUnit == null) return Optional.empty();
        String s = raw.trim();
        if (s.isEmpty()) return Optional.empty();

        // A half-open Partsbox range with no lower bound ("null..X") collapses to its single defined
        // value X. Other ranges ("X..Y", "X..null") stay unparseable and are fixed by hand.
        if (s.regionMatches(true, 0, "null..", 0, 6)) {
            s = s.substring(6).trim();
            if (s.isEmpty()) return Optional.empty();
        }

        Matcher m = NUMBER.matcher(s);
        if (!m.find()) return Optional.empty();

        double num;
        try {
            num = Double.parseDouble(m.group());
        } catch (NumberFormatException e) {
            return Optional.empty();
        }

        String rest = s.substring(m.end()).trim();
        Integer exp = matchPrefix(rest, baseUnit.trim());
        if (exp == null) return Optional.empty();

        double base = num * Math.pow(10, exp);
        return Optional.of(BigDecimal.valueOf(base).stripTrailingZeros().toPlainString());
    }

    /**
     * Exponent for the unit tail after the number: 0 if empty (bare number) or exactly the base unit;
     * the prefix exponent if it is {@code <one prefix char> + baseUnit}; null if it matches neither.
     */
    private static Integer matchPrefix(String rest, String baseUnit) {
        if (rest.isEmpty()) return 0;
        if (rest.equalsIgnoreCase(baseUnit)) return 0;
        if (rest.length() > baseUnit.length()) {
            String prefix = rest.substring(0, rest.length() - baseUnit.length());
            String unitPart = rest.substring(prefix.length());
            if (prefix.length() == 1 && unitPart.equalsIgnoreCase(baseUnit)) {
                return PREFIX_EXP.get(prefix);
            }
        }
        return null;
    }

    /**
     * Best-effort guess of the base unit for a set of values: strip the leading number and a single
     * leading prefix char, then return the most common remaining alphabetic token. Empty string if none.
     */
    public static String suggestUnit(Collection<String> values) {
        Map<String, Integer> tally = new LinkedHashMap<>();
        for (String raw : values) {
            if (raw == null) continue;
            String s = raw.trim();
            Matcher m = NUMBER.matcher(s);
            if (!m.find()) continue;
            String rest = s.substring(m.end()).trim();
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
