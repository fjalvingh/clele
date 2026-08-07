package com.clele.parts.service.bom;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Normalises a BOM line's designator list into the key a re-import pairs on.
 *
 * <p>The designators are what identifies a line across revisions — the value and footprint of C7
 * may change, but it is still C7. Normalising (uppercase, sorted, single separator) means a file
 * that writes "C3, C1,C2" pairs with one that wrote "C1,C2,C3"; without that, a re-export with the
 * grouping in a different order would look like an entirely new set of lines and every confirmed
 * match would be lost.
 *
 * <p>Sorting is <em>natural</em>: C2 before C10, not after it. That is only cosmetic for pairing
 * (any total order pairs correctly, as long as it is the same one on both sides) but it keeps the
 * stored key readable, which matters when diagnosing a merge that paired the wrong lines.
 */
public final class DesignatorKey {

    private static final Pattern SEPARATORS = Pattern.compile("[,;\\s]+");
    private static final Pattern ALPHA_NUMERIC = Pattern.compile("^([^0-9]*)([0-9]+)(.*)$");

    /** Compares "C2" before "C10" — alpha prefix first, then the number as a number. */
    private static final Comparator<String> NATURAL = (a, b) -> {
        Matcher ma = ALPHA_NUMERIC.matcher(a);
        Matcher mb = ALPHA_NUMERIC.matcher(b);
        if (ma.matches() && mb.matches()) {
            int byPrefix = ma.group(1).compareTo(mb.group(1));
            if (byPrefix != 0) {
                return byPrefix;
            }
            // Strip leading zeros before comparing as a number; a designator numbered beyond
            // long range is not a thing, but parse defensively rather than trusting the file.
            int byNumber = compareNumeric(ma.group(2), mb.group(2));
            if (byNumber != 0) {
                return byNumber;
            }
            return ma.group(3).compareTo(mb.group(3));
        }
        return a.compareTo(b);
    };

    private DesignatorKey() {
    }

    /** The individual designators, uppercased and naturally sorted. Empty for blank input. */
    public static List<String> split(String designators) {
        if (designators == null || designators.isBlank()) {
            return List.of();
        }
        return Arrays.stream(SEPARATORS.split(designators.trim()))
                .filter(s -> !s.isBlank())
                .map(s -> s.toUpperCase().trim())
                .distinct()
                .sorted(NATURAL)
                .collect(Collectors.toList());
    }

    /**
     * The merge key for a designator list: the normalised designators joined with ",". Blank for
     * input that holds no designators — the caller then falls back to a key built from the line's
     * MPN/value/position, since the key column is NOT NULL and unique within a BOM.
     */
    public static String normalize(String designators) {
        return String.join(",", split(designators));
    }

    /** How many parts this line covers, when the file carries no quantity column. */
    public static int count(String designators) {
        return split(designators).size();
    }

    private static int compareNumeric(String a, String b) {
        String sa = a.replaceFirst("^0+(?=.)", "");
        String sb = b.replaceFirst("^0+(?=.)", "");
        if (sa.length() != sb.length()) {
            return Integer.compare(sa.length(), sb.length());
        }
        return sa.compareTo(sb);
    }
}
