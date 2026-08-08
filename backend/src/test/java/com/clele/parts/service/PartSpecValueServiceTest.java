package com.clele.parts.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the range-separator rules. The rest of {@code PartSpecValueService} needs a database to say
 * anything true (a mock cannot reproduce an upsert), so it is verified end to end instead; this is
 * the part that is pure logic and whose failure mode — inventing bounds nobody wrote — is silent.
 */
class PartSpecValueServiceTest {

    @Test
    @DisplayName("the Partsbox form, which is the bulk of the data")
    void partsboxDoubleDot() {
        assertThat(PartSpecValueService.splitRange("3..16")).containsExactly("3", "16");
        assertThat(PartSpecValueService.splitRange("4.5..null")).containsExactly("4.5", "null");
        assertThat(PartSpecValueService.splitRange("null..200")).containsExactly("null", "200");
        // A decimal bound must not be mistaken for the separator.
        assertThat(PartSpecValueService.splitRange("1.2..3.4")).containsExactly("1.2", "3.4");
    }

    @Test
    @DisplayName("the component cache's own display rendering — a live source, not just the backlog")
    void tildeForm() {
        assertThat(PartSpecValueService.splitRange("-40.0 °C ~ 105.0 °C"))
                .containsExactly("-40.0 °C ", " 105.0 °C");
    }

    @Test
    @DisplayName("the datasheet form")
    void toForm() {
        assertThat(PartSpecValueService.splitRange("15 V to 35 V")).containsExactly("15 V", "35 V");
        assertThat(PartSpecValueService.splitRange("-20°C to +70°C")).containsExactly("-20°C", "+70°C");
        assertThat(PartSpecValueService.splitRange("0°C TO 125°C")).containsExactly("0°C", "125°C");
    }

    @Test
    @DisplayName("'to' needs its spaces, or it matches inside a word")
    void toRequiresWhitespace() {
        assertThat(PartSpecValueService.splitRange("Autotransformer")).isNull();
        assertThat(PartSpecValueService.splitRange("TO-220")).isNull();
    }

    @Test
    @DisplayName("a hyphen is not a separator — it cannot be told from a negative number")
    void hyphenIsNotARange() {
        assertThat(PartSpecValueService.splitRange("-40-125")).isNull();
        assertThat(PartSpecValueService.splitRange("-55")).isNull();
    }

    @Test
    @DisplayName("a numeric-looking string converts only when the round trip is lossless")
    void losslessNumericStrings() {
        // "0805" is an imperial case code in a family-less field. Read as the number 805 it loses
        // both its value and its place in the free-text search, so searching "0805" stops finding
        // the part — which is exactly what happened before this rule.
        assertThat(PartSpecValueService.numericIfLossless("0805")).isNull();
        assertThat(PartSpecValueService.numericIfLossless("007")).isNull();
        assertThat(PartSpecValueService.numericIfLossless("1.50")).isNull();
        assertThat(PartSpecValueService.numericIfLossless("2012")).isNotNull();
        assertThat(PartSpecValueService.numericIfLossless("4700")).isNotNull();
        assertThat(PartSpecValueService.numericIfLossless("0.4")).isNotNull();
        assertThat(PartSpecValueService.numericIfLossless("-1.26")).isNotNull();
        assertThat(PartSpecValueService.numericIfLossless("X7R")).isNull();
    }

    @Test
    void plainValuesAreNotRanges() {
        assertThat(PartSpecValueService.splitRange("4700")).isNull();
        assertThat(PartSpecValueService.splitRange("X7R")).isNull();
        assertThat(PartSpecValueService.splitRange("5V ± 10%")).isNull();
        assertThat(PartSpecValueService.splitRange("4k7")).isNull();
    }
}
