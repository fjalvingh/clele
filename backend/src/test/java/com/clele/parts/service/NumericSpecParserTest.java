package com.clele.parts.service;

import com.clele.parts.model.UnitFamily;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins what a NUMBER spec field accepts — and, just as much, what it refuses.
 *
 * <p>Both halves matter now that the data type is authoritative: an accepted spelling is a value
 * that answers parametric search, and a refused one is a value that is <b>dropped</b> rather than
 * parked in {@code value_text} where it would only display. Every example here is a value that
 * actually occurs in the catalogue.
 */
class NumericSpecParserTest {

    private static Optional<NumericSpecParser.Parsed> parse(String raw, UnitFamily family) {
        return NumericSpecParser.parse(raw, family, null);
    }

    private static void assertValue(String raw, UnitFamily family, String num, String min, String max) {
        NumericSpecParser.Parsed p = parse(raw, family).orElseThrow(() ->
                new AssertionError("refused: " + raw));
        assertThat(strip(p.num())).as("nominal of %s", raw).isEqualTo(num);
        assertThat(strip(p.min())).as("min of %s", raw).isEqualTo(min);
        assertThat(strip(p.max())).as("max of %s", raw).isEqualTo(max);
    }

    private static String strip(BigDecimal v) {
        return v == null ? null : v.stripTrailingZeros().toPlainString();
    }

    @Test
    @DisplayName("a plain value, in the family's own spellings")
    void scalars() {
        assertValue("5 V", UnitFamily.VOLTAGE, "5", null, null);
        assertValue("12V", UnitFamily.VOLTAGE, "12", null, null);
        assertValue("800 mV", UnitFamily.VOLTAGE, "0.8", null, null);
        assertValue("4k7", UnitFamily.RESISTANCE, "4700", null, null);
        assertValue("100nF", UnitFamily.CAPACITANCE, "0.0000001", null, null);
        // A bare number is already in the base unit.
        assertValue("16", UnitFamily.CURRENT, "16", null, null);
    }

    @Test
    @DisplayName("unit words are folded to their symbols")
    void unitWords() {
        assertValue("3.7 ohms", UnitFamily.RESISTANCE, "3.7", null, null);
        assertValue("1500 microseconds", UnitFamily.TIME, "0.0015", null, null);
        assertValue("5 µsec", UnitFamily.TIME, "0.000005", null, null);
        assertValue("1.8 degrees", UnitFamily.ANGLE, "1.8", null, null);
    }

    @Test
    @DisplayName("qualifiers and notes that do not change the reading are dropped")
    void qualifiers() {
        assertValue("5V DC", UnitFamily.VOLTAGE, "5", null, null);
        assertValue("1.4 A RMS", UnitFamily.CURRENT, "1.4", null, null);
        assertValue("220 mA (6V, no-load)", UnitFamily.CURRENT, "0.22", null, null);
        assertValue("7.2 mA typ", UnitFamily.CURRENT, "0.0072", null, null);
    }

    @Test
    @DisplayName("the written range forms, an unreadable component staying open")
    void writtenRanges() {
        assertValue("3..16", UnitFamily.VOLTAGE, null, "3", "16");
        assertValue("4.5..null", UnitFamily.VOLTAGE, null, "4.5", null);
        assertValue("null..200", UnitFamily.VOLTAGE, null, null, "200");
        assertValue("4.5..5..5.5", UnitFamily.VOLTAGE, "5", "4.5", "5.5");
        assertValue("-40.0 °C ~ 105.0 °C", UnitFamily.TEMPERATURE, null, "-40", "105");
        assertValue("15 V to 35 V", UnitFamily.VOLTAGE, null, "15", "35");
        assertValue("-20°C to +70°C", UnitFamily.TEMPERATURE, null, "-20", "70");
    }

    @Test
    @DisplayName("a hyphen or dash range — only when both halves read as numbers")
    void dashRanges() {
        assertValue("4.8-6.0 V", UnitFamily.VOLTAGE, null, "4.8", "6");
        assertValue("1.5-3V", UnitFamily.VOLTAGE, null, "1.5", "3");
        assertValue("500-2500 µs", UnitFamily.TIME, null, "0.0005", "0.0025");
        assertValue("9-40 VDC", UnitFamily.VOLTAGE, null, "9", "40");
        assertValue("-15–70 °C", UnitFamily.TEMPERATURE, null, "-15", "70");
        // ⚠️ The reason a leading hyphen never separates: this is one negative number's worth of
        // ambiguity, and inventing bounds from it would be worse than refusing.
        assertThat(parse("-40-125", UnitFamily.TEMPERATURE)).isEmpty();
        // Scientific notation must survive the hyphen rule.
        assertValue("1e-7", UnitFamily.CAPACITANCE, "0.0000001", null, null);
    }

    @Test
    @DisplayName("a tolerance becomes the band it describes")
    void tolerances() {
        assertValue("5V ± 10%", UnitFamily.VOLTAGE, "5", "4.5", "5.5");
        assertValue("180° ± 3°", UnitFamily.ANGLE, "180", "177", "183");
        assertValue("5 V +/- 0.25 V", UnitFamily.VOLTAGE, "5", "4.75", "5.25");
    }

    @Test
    @DisplayName("one-sided values keep the bound they state and leave the other open")
    void oneSided() {
        assertValue("> 600 Hz", UnitFamily.FREQUENCY, null, "600", null);
        assertValue("≤ 5 V", UnitFamily.VOLTAGE, null, null, "5");
        assertValue("up to 50 W", UnitFamily.POWER, null, null, "50");
        assertValue("5 V max", UnitFamily.VOLTAGE, null, null, "5");
        assertValue("min 2.7 V", UnitFamily.VOLTAGE, null, "2.7", null);
    }

    @Test
    @DisplayName("what is refused — and so dropped rather than shown as an unusable value")
    void refusals() {
        assertThat(parse("5.5-6volters", UnitFamily.VOLTAGE)).isEmpty();   // a typo
        assertThat(parse("Asynchronous", UnitFamily.FREQUENCY)).isEmpty(); // not a value at all
        assertThat(parse("5.625°/64", UnitFamily.ANGLE)).isEmpty();        // per-something
        assertThat(parse("2K x 8", null)).isEmpty();                       // an organisation
        assertThat(parse("", UnitFamily.VOLTAGE)).isEmpty();
    }

    @Test
    @DisplayName("with no family and no declared unit, only a plain number is safe to read")
    void noFamilyNoUnit() {
        assertThat(strip(NumericSpecParser.parse("4700", null, null).orElseThrow().num()))
                .isEqualTo("4700");
        // ⚠️ Nothing says what this field counts, so there is no scale to convert to: 16 mA could
        // as easily mean 0.016 as 16, and guessing is how a 4 KB memory becomes 4000.
        assertThat(NumericSpecParser.parse("16 mA", null, null)).isEmpty();
        // A field that declares its unit the old way (unit + metric_prefix) can read it.
        assertThat(strip(NumericSpecParser.parse("16 mA", null, "A").orElseThrow().num()))
                .isEqualTo("0.016");
    }
}
