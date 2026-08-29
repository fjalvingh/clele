package com.clele.parts.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the rule that decides an auto-created definition's data type — and with it, since the type
 * became authoritative, whether that key's values are ever read as numbers at all.
 *
 * <p>The range and unit spellings moved to {@link NumericSpecParserTest} with the parser. The rest
 * of {@code PartSpecValueService} needs a database to say anything true (a mock cannot reproduce an
 * upsert), so it is verified end to end instead.
 */
class PartSpecValueServiceTest {

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

}
