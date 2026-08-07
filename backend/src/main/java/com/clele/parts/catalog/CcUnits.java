package com.clele.parts.catalog;

import java.util.Map;
import java.util.Set;

/**
 * What a {@code cc_attribute_def.unit} family actually measures.
 *
 * <p>The cache stores numbers in SI base units — 100 nF is {@code 1e-7}, 1 mm is {@code 0.001},
 * 16 MHz is {@code 16000000} — but records only the <em>family</em> ("capacitance", "length"), never
 * a symbol. This app's own NUMBER specs declare a literal unit instead ("mm", "kΩ", "°C", or nothing
 * at all). Turning one into the other needs the family's base symbol, which is what this table is.
 *
 * <p><b>Only unambiguous families are listed.</b> An unlisted family is not an error and not a gap
 * to be filled in later out of tidiness: it is the signal to fall back to the cache's own rendered
 * {@code display} string, which carries its unit and therefore cannot misstate a magnitude. Adding a
 * questionable entry here is how a 4 KB memory becomes 4000, so leave a family out unless its base
 * unit is genuinely certain.
 */
final class CcUnits {

    private CcUnits() {}

    /** Family -> SI base unit symbol, for families whose numeric value scales with an SI prefix. */
    private static final Map<String, String> SI_BASE = Map.ofEntries(
            Map.entry("voltage", "V"),
            Map.entry("current", "A"),
            Map.entry("resistance", "Ω"),
            Map.entry("capacitance", "F"),
            Map.entry("inductance", "H"),
            Map.entry("frequency", "Hz"),
            Map.entry("time", "s"),
            Map.entry("power", "W"),
            Map.entry("energy", "J"),
            Map.entry("charge", "C"),
            Map.entry("length", "m"),
            Map.entry("magnetic_flux_density", "T"),
            Map.entry("luminous_intensity", "cd"),
            Map.entry("luminous_flux", "lm"),
            Map.entry("force", "N"),
            Map.entry("pressure", "Pa"));

    /**
     * Families whose bare number means the same thing with or without a declared unit — counts,
     * proportions and absolute scales that nobody writes with an SI prefix.
     *
     * <p>These are the only families safe to store as a plain number against a spec field that
     * declares <em>no</em> unit. For a scalable family ("voltage", "length", …) a unit-less number is
     * meaningless on its own: 0.0016 is either 1.6 mm or 1.6 km depending on a convention nothing
     * records, so those fall back to {@code display} instead.
     */
    private static final Set<String> SCALE_FREE = Set.of(
            "count", "percentage", "ratio", "ppm", "decibel", "decibel_milliwatt",
            "awg", "lsb", "temperature", "kelvin", "angle", "area_mm2");

    /** The SI base symbol for a family, or null when this app should use {@code display} instead. */
    static String siBase(String family) {
        return family == null ? null : SI_BASE.get(family);
    }

    /** Whether a bare number from this family is meaningful without a declared unit. */
    static boolean isScaleFree(String family) {
        return family != null && SCALE_FREE.contains(family);
    }

    /** The families holding text rather than a measurement. */
    static boolean isTextual(String family) {
        return "identifier".equals(family) || "string".equals(family);
    }
}
