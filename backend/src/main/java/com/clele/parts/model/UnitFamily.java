package com.clele.parts.model;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * What a spec field measures — the vocabulary stored in {@code spec_definition.unit_family}.
 *
 * <p>A family is what <em>licenses</em> parsing a spec value string into a number: knowing that
 * {@code capacitance} is measured in farads is what turns {@code "100nF"} into {@code 1e-7}. A
 * definition with no family never has its values parsed; they stay text, which is the safe default
 * and deliberately not an error (see {@code SPECS-REWRITE.md}).
 *
 * <p>The codes mirror the families of the component-cache snapshot ({@code CcUnits}) so the two
 * translations agree, but this enum carries more: the <b>prefix window</b> and the <b>RKM</b>
 * rendering style, neither of which the cache needs.
 *
 * <h2>The prefix window</h2>
 *
 * {@link #minExp}/{@link #maxExp} bound the SI prefixes accepted in the <em>bare-letter</em> forms
 * ({@code 4k7}, {@code 100n}) and produced by the renderer. It exists because {@code 4m7} and
 * {@code 4M7} differ by nine orders of magnitude and by one shift key, with no unit symbol present
 * to make the mistake visible: resistance therefore refuses {@code m µ n p} and capacitance and
 * inductance refuse {@code k M G T}. A genuine milliohm value is written in the base unit
 * ({@code "0.0047R"}, or plain {@code "0.0047"}).
 *
 * <p><b>The window binds the renderer too, and that is not tidiness.</b> {@code draintosourceresistance}
 * really does hold values like {@code 0.0087}; an unrestricted engineering renderer would print
 * {@code "8m7"}, which the parser is now required to refuse — the round trip would be broken by the
 * very rule meant to protect it. Outside the window the value renders as a decimal in the base unit.
 *
 * <p>The window applies only to the bare letter. {@code "15 mΩ"} — where the symbol is written out,
 * so the reader and the parser see the same thing — parses normally, because the component cache and
 * the datasheet extractor both emit that form and sub-ohm resistance is ordinary (RDS(on), ESR,
 * contact resistance, DCR). See {@code MetricUnitParser}.
 */
public enum UnitFamily {

    // --- Scalable families: an SI base unit, values carry prefixes. -----------------------------

    VOLTAGE("voltage", "V"),
    CURRENT("current", "A"),

    /** RKM: {@code 4k7}, {@code 100R}, {@code 4M7}. Refuses {@code m µ n p} — see class docs. */
    RESISTANCE("resistance", "Ω", 0, 12, true, "R"),
    /** RKM: {@code 2n2}, {@code 100n}, {@code 1u5}. Refuses {@code k M G T}. */
    CAPACITANCE("capacitance", "F", -12, 0, true, "F"),
    /** RKM: {@code 2u2}, {@code 4m7}. Refuses {@code k M G T}, and {@code R} (see below). */
    INDUCTANCE("inductance", "H", -12, 0, true, "H"),

    FREQUENCY("frequency", "Hz"),
    TIME("time", "s"),
    POWER("power", "W"),
    ENERGY("energy", "J"),
    CHARGE("charge", "C"),
    LENGTH("length", "m"),
    MAGNETIC_FLUX_DENSITY("magnetic_flux_density", "T"),
    LUMINOUS_INTENSITY("luminous_intensity", "cd"),
    LUMINOUS_FLUX("luminous_flux", "lm"),
    FORCE("force", "N"),
    PRESSURE("pressure", "Pa"),

    // --- Scale-free families: a bare number means the same with or without a unit. ---------------
    // Nobody writes these with an SI prefix, so the window is pinned shut at 10^0.

    COUNT("count", "", true),
    PERCENTAGE("percentage", "%", true),
    RATIO("ratio", "", true),
    PPM("ppm", "ppm", true),
    DECIBEL("decibel", "dB", true),
    DECIBEL_MILLIWATT("decibel_milliwatt", "dBm", true),
    AWG("awg", "AWG", true),
    LSB("lsb", "LSB", true),
    TEMPERATURE("temperature", "°C", true),
    KELVIN("kelvin", "K", true),
    ANGLE("angle", "°", true),
    AREA_MM2("area_mm2", "mm²", true),
    /** Thermal resistance — °C/W, emphatically <em>not</em> {@link #RESISTANCE}. */
    THERMAL_RESISTANCE("thermal_resistance", "°C/W", true);

    private final String code;
    private final String baseUnit;
    private final int minExp;
    private final int maxExp;
    private final boolean rkm;
    private final String baseMarker;

    /** A scalable family with the full prefix range and ordinary "9 mA" rendering. */
    UnitFamily(String code, String baseUnit) {
        this(code, baseUnit, -12, 12, false, baseUnit);
    }

    /**
     * A scale-free family: the window is pinned shut at 10^0, so no prefix is ever accepted or
     * produced. This is not cosmetic — a percentage or a °C reading written {@code 4m7} is a typo,
     * not four-point-seven milli-anything, and silently scaling it is how a 4 KB memory becomes 4000.
     */
    UnitFamily(String code, String baseUnit, boolean scaleFree) {
        this(code, baseUnit, 0, 0, false, baseUnit);
    }

    UnitFamily(String code, String baseUnit, int minExp, int maxExp, boolean rkm, String baseMarker) {
        this.code = code;
        this.baseUnit = baseUnit;
        this.minExp = minExp;
        this.maxExp = maxExp;
        this.rkm = rkm;
        this.baseMarker = baseMarker;
    }

    private static final Map<String, UnitFamily> BY_CODE = Arrays.stream(values())
            .collect(Collectors.toMap(UnitFamily::getCode, Function.identity()));

    /** The value stored in {@code spec_definition.unit_family}. */
    public String getCode() {
        return code;
    }

    /** SI base symbol values are stored in — "Ω", "F", "m". Empty for a dimensionless family. */
    public String getBaseUnit() {
        return baseUnit;
    }

    /**
     * The letter standing in for the decimal point at the base-unit position: {@code "R"} for
     * resistance ({@code 4R7}), the unit symbol otherwise ({@code 4V7}, {@code 2F2}).
     *
     * <p>⚠️ {@code R} is deliberately <b>not</b> generalised. On an SMD inductor {@code 4R7}
     * conventionally means 4.7 <b>µH</b>, with the µ implied by the component class rather than
     * written; reading that implication is the same class of error as taking 4 KB for 4000, so an
     * {@code R} in an inductance field does not parse and the value stays text.
     */
    public String getBaseMarker() {
        return baseMarker;
    }

    /** Whether values of this family render in RKM code ({@code 4k7}) rather than as "4.7 kΩ". */
    public boolean isRkm() {
        return rkm;
    }

    /** A family whose bare number is meaningful on its own; no prefix is ever accepted or produced. */
    public boolean isScaleFree() {
        return minExp == 0 && maxExp == 0;
    }

    /** Whether a bare-letter prefix of this power of ten is accepted, and may be rendered. */
    public boolean allowsExponent(int exp) {
        return exp >= minExp && exp <= maxExp;
    }

    public static Optional<UnitFamily> byCode(String code) {
        return code == null ? Optional.empty() : Optional.ofNullable(BY_CODE.get(code.trim()));
    }
}
