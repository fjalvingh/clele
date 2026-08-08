package com.clele.parts.service;

import com.clele.parts.model.UnitFamily;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class MetricUnitParserTest {

    private static Optional<String> parse(String raw, UnitFamily family) {
        return MetricUnitParser.parseToBase(raw, family);
    }

    @Nested
    @DisplayName("the forms that already worked keep working")
    class Existing {

        @Test
        void bareNumberIsAlreadyInTheBaseUnit() {
            assertThat(parse("4700", UnitFamily.RESISTANCE)).contains("4700");
        }

        @Test
        void numberWithUnit() {
            assertThat(parse("4700 Ω", UnitFamily.RESISTANCE)).contains("4700");
            assertThat(parse("3.3V", UnitFamily.VOLTAGE)).contains("3.3");
        }

        @Test
        void numberWithPrefixAndUnit() {
            assertThat(parse("9 mA", UnitFamily.CURRENT)).contains("0.009");
            assertThat(parse("100nF", UnitFamily.CAPACITANCE)).contains("0.0000001");
            assertThat(parse("4.7 kΩ", UnitFamily.RESISTANCE)).contains("4700");
        }

        @Test
        void halfOpenPartsboxRangeCollapsesToItsSingleValue() {
            assertThat(parse("null..16", UnitFamily.VOLTAGE)).contains("16");
        }

        @Test
        void factorToBaseIsUnchanged() {
            assertThat(MetricUnitParser.factorToBase("mm", "m")).contains(0.001);
            assertThat(MetricUnitParser.factorToBase("kΩ", "Ω")).contains(1000.0);
            assertThat(MetricUnitParser.factorToBase("°C", "V")).isEmpty();
        }
    }

    @Nested
    @DisplayName("RKM code — the letter is the decimal point")
    class Rkm {

        @Test
        void infixOnResistance() {
            assertThat(parse("1K2", UnitFamily.RESISTANCE)).contains("1200");
            assertThat(parse("4k7", UnitFamily.RESISTANCE)).contains("4700");
            assertThat(parse("4R7", UnitFamily.RESISTANCE)).contains("4.7");
            assertThat(parse("4M7", UnitFamily.RESISTANCE)).contains("4700000");
            assertThat(parse("4K75", UnitFamily.RESISTANCE)).contains("4750");
        }

        @Test
        void infixOnCapacitanceAndInductance() {
            assertThat(parse("2n2", UnitFamily.CAPACITANCE)).contains("0.0000000022");
            assertThat(parse("1u5", UnitFamily.CAPACITANCE)).contains("0.0000015");
            assertThat(parse("1µ5", UnitFamily.CAPACITANCE)).contains("0.0000015");
            assertThat(parse("4m7", UnitFamily.INDUCTANCE)).contains("0.0047");
        }

        @Test
        void trailingLetterWithNoFractionalPart() {
            assertThat(parse("100R", UnitFamily.RESISTANCE)).contains("100");
            assertThat(parse("47k", UnitFamily.RESISTANCE)).contains("47000");
            assertThat(parse("100n", UnitFamily.CAPACITANCE)).contains("0.0000001");
        }

        @Test
        void negativeValues() {
            assertThat(parse("-4k7", UnitFamily.RESISTANCE)).contains("-4700");
        }

        @Test
        @DisplayName("a decimal mantissa is not RKM — '4.7k7' is a typo, not a number")
        void decimalMantissaIsRejected() {
            assertThat(parse("4.7k7", UnitFamily.RESISTANCE)).isEmpty();
        }

        @Test
        @DisplayName("RKM is accepted for every family with a base unit, not only the three")
        void parseWidelyEvenWhereRenderingIsOrdinary() {
            assertThat(parse("1M2", UnitFamily.FREQUENCY)).contains("1200000");
            assertThat(parse("4V7", UnitFamily.VOLTAGE)).contains("4.7");
            assertThat(parse("150n", UnitFamily.TIME)).contains("0.00000015");
        }
    }

    @Nested
    @DisplayName("the family prefix window")
    class Window {

        @Test
        @DisplayName("resistance refuses a bare milli/micro/nano/pico letter")
        void resistanceRefusesSmallBareLetters() {
            assertThat(parse("4m7", UnitFamily.RESISTANCE)).isEmpty();
            assertThat(parse("100m", UnitFamily.RESISTANCE)).isEmpty();
            assertThat(parse("4u7", UnitFamily.RESISTANCE)).isEmpty();
        }

        @Test
        @DisplayName("capacitance and inductance refuse a bare kilo/mega letter")
        void smallFamiliesRefuseLargeBareLetters() {
            assertThat(parse("4M7", UnitFamily.CAPACITANCE)).isEmpty();
            assertThat(parse("47k", UnitFamily.CAPACITANCE)).isEmpty();
            assertThat(parse("1K2", UnitFamily.INDUCTANCE)).isEmpty();
        }

        @Test
        @DisplayName("the explicit unit-symbol form is still accepted — RDS(on), ESR, DCR live here")
        void explicitUnitSymbolBypassesTheWindow() {
            assertThat(parse("15 mΩ", UnitFamily.RESISTANCE)).contains("0.015");
            assertThat(parse("4.7mΩ", UnitFamily.RESISTANCE)).contains("0.0047");
        }

        @Test
        @DisplayName("R is a resistance marker only — '4R7' on an inductor means 4.7 µH by a "
                + "convention we refuse to read")
        void baseMarkerIsNotGeneralised() {
            assertThat(parse("4R7", UnitFamily.INDUCTANCE)).isEmpty();
            assertThat(parse("4R7", UnitFamily.CAPACITANCE)).isEmpty();
            assertThat(parse("100R", UnitFamily.VOLTAGE)).isEmpty();
        }

        @Test
        @DisplayName("case stays load-bearing: 4M7 and 4m7 differ by nine orders of magnitude")
        void caseIsSignificant() {
            assertThat(parse("4M7", UnitFamily.RESISTANCE)).contains("4700000");
            assertThat(parse("4m7", UnitFamily.RESISTANCE)).isEmpty();
            assertThat(parse("4m7", UnitFamily.INDUCTANCE)).contains("0.0047");
            assertThat(parse("4M7", UnitFamily.INDUCTANCE)).isEmpty();
        }

        @Test
        void scaleFreeFamiliesTakeNoPrefixAtAll() {
            assertThat(parse("40", UnitFamily.TEMPERATURE)).contains("40");
            assertThat(parse("40 °C", UnitFamily.TEMPERATURE)).contains("40");
            assertThat(parse("4m7", UnitFamily.TEMPERATURE)).isEmpty();
        }
    }

    @Nested
    @DisplayName("round trip — everything the formatter produces, the parser reads back")
    class RoundTrip {

        private void roundTrip(String stored, UnitFamily family, String expectedRendering) {
            String rendered = MetricUnitFormatter.format(new BigDecimal(stored), family);
            assertThat(rendered).isEqualTo(expectedRendering);
            assertThat(parse(rendered, family))
                    .as("re-parsing %s", rendered)
                    .contains(new BigDecimal(stored).stripTrailingZeros().toPlainString());
        }

        @Test
        void resistanceRendersRkm() {
            roundTrip("4700", UnitFamily.RESISTANCE, "4k7");
            roundTrip("47000", UnitFamily.RESISTANCE, "47k");
            roundTrip("100", UnitFamily.RESISTANCE, "100R");
            roundTrip("4700000", UnitFamily.RESISTANCE, "4M7");
            roundTrip("4.7", UnitFamily.RESISTANCE, "4R7");
        }

        @Test
        void capacitanceAndInductanceRenderRkm() {
            roundTrip("0.0000001", UnitFamily.CAPACITANCE, "100n");
            roundTrip("0.0000000022", UnitFamily.CAPACITANCE, "2n2");
            roundTrip("0.0000015", UnitFamily.CAPACITANCE, "1µ5");
            roundTrip("0.0047", UnitFamily.INDUCTANCE, "4m7");
        }

        @Test
        @DisplayName("below the window the decimal point stays and the marker suffixes: 0.0087R")
        void subOhmValuesRenderInTheBaseUnit() {
            roundTrip("0.0087", UnitFamily.RESISTANCE, "0.0087R");
            roundTrip("0.009", UnitFamily.RESISTANCE, "0.009R");
            roundTrip("0.03", UnitFamily.RESISTANCE, "0.03R");
        }

        @Test
        void ordinaryFamiliesRenderWithTheUnit() {
            roundTrip("0.009", UnitFamily.CURRENT, "9 mA");
            roundTrip("3.3", UnitFamily.VOLTAGE, "3.3 V");
            roundTrip("0.00000015", UnitFamily.TIME, "150 ns");
            roundTrip("16000000", UnitFamily.FREQUENCY, "16 MHz");
            roundTrip("0.001", UnitFamily.LENGTH, "1 mm");
        }

        @Test
        void zeroAndScaleFree() {
            roundTrip("0", UnitFamily.RESISTANCE, "0R");
            roundTrip("40", UnitFamily.TEMPERATURE, "40 °C");
        }
    }
}
