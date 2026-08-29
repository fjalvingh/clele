package com.clele.parts.mcp;

import com.clele.parts.model.SpecDefinition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a model is shown for a stored spec value. The raw number is what a follow-up query compares
 * against, so the display string is allowed to be readable but never to replace it.
 */
class SpecValueRendererTest {

    private static SpecDefinition field(String jsonName, String family, String unit) {
        return SpecDefinition.builder()
                .jsonName(jsonName)
                .name(jsonName)
                .dataType(family == null ? "TEXT" : "NUMBER")
                .unitFamily(family)
                .unit(unit)
                .build();
    }

    @Test
    @DisplayName("a base-unit number renders in the form an engineer writes")
    void rendersScalars() {
        assertThat(SpecValueRenderer.display(new BigDecimal("1E-7"), field("capacitance", "capacitance", null)))
                .isEqualTo("100n");
        assertThat(SpecValueRenderer.display(new BigDecimal("4700"), field("resistance", "resistance", null)))
                .isEqualTo("4k7");
        assertThat(SpecValueRenderer.display(new BigDecimal("3.3"), field("supplyvoltage", "voltage", null)))
                .isEqualTo("3.3 V");
    }

    @Test
    @DisplayName("a range renders both ends, an open one as an inequality")
    void rendersRanges() {
        SpecDefinition voltage = field("supplyvoltage", "voltage", null);
        assertThat(SpecValueRenderer.display("2..5.5", voltage)).isEqualTo("2 V ~ 5.5 V");
        assertThat(SpecValueRenderer.display("null..16", voltage)).isEqualTo("≤ 16 V");
        assertThat(SpecValueRenderer.display("600..null", voltage)).isEqualTo("≥ 600 V");
    }

    @Test
    @DisplayName("a nominal with bounds keeps the nominal in front — it is the value being stated")
    void rendersNominalWithBounds() {
        assertThat(SpecValueRenderer.display("4.5..5..5.5", field("supplyvoltage", "voltage", null)))
                .isEqualTo("5 V (4.5 V ~ 5.5 V)");
    }

    @Test
    @DisplayName("a field with no family renders the number with its declared unit, or bare")
    void rendersWithoutFamily() {
        assertThat(SpecValueRenderer.display(new BigDecimal("8"), field("pincount", null, null)))
                .isEqualTo("8");
        assertThat(SpecValueRenderer.display(new BigDecimal("2.54"), field("pitch", null, "mm")))
                .isEqualTo("2.54 mm");
    }

    @Test
    @DisplayName("text passes through — including text that happens to contain '..'")
    void passesTextThrough() {
        SpecDefinition packageField = field("package", null, null);
        assertThat(SpecValueRenderer.display("SOT-23", packageField)).isEqualTo("SOT-23");
        assertThat(SpecValueRenderer.display("0805", packageField)).isEqualTo("0805");
        assertThat(SpecValueRenderer.display("A..Z", packageField)).isEqualTo("A..Z");
        assertThat(SpecValueRenderer.display(null, packageField)).isEmpty();
    }
}
