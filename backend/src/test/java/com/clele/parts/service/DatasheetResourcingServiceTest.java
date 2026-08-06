package com.clele.parts.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Pins the part-mention gate, which is the only thing standing between a web search result and
 * {@code part.datasheet_url}. A search for a part number happily returns a datasheet for a different
 * part; accepting one would quietly poison any spec extraction built on top of it.
 */
class DatasheetResourcingServiceTest {

    @Test
    void matchesTheWholePartNumber() {
        assertEquals("SN74LS174N",
                DatasheetResourcingService.mentionOf("SN74LS174N", "... SN74LS174N, SN74LS175N ..."));
    }

    @Test
    void ignoresCaseAndPunctuationOnBothSides() {
        assertEquals("MC1489P", DatasheetResourcingService.mentionOf("MC1489P.", "The MC-1489-P is a quad receiver"));
        assertEquals("BC856B", DatasheetResourcingService.mentionOf("bc856b", "TYPE: BC 856 B"));
    }

    /** Datasheets routinely cover a family and print the base number without the package suffix. */
    @Test
    void fallsBackToAPrefixWhenTheSuffixIsAbsent() {
        assertEquals("SN74LS30",
                DatasheetResourcingService.mentionOf("SN74LS30N", "SN54LS30, SN74LS30 8-INPUT NAND GATE"));
    }

    @Test
    void rejectsADatasheetForADifferentPart() {
        assertNull(DatasheetResourcingService.mentionOf(
                "SN74LS174N", "CD4017B CMOS Decade Counter/Divider with 10 Decoded Outputs"));
    }

    /**
     * The prefix fallback must not erode into meaninglessness: three characters of "SN7" would match
     * every 74-series datasheet ever printed, so matching stops at five.
     */
    @Test
    void doesNotAcceptATooShortPrefix() {
        assertNull(DatasheetResourcingService.mentionOf("SN74LS174N", "SN74 family overview"));
        assertNull(DatasheetResourcingService.mentionOf("AM26", "AM26LS31 quad driver"));
    }

    @Test
    void handlesMissingInput() {
        assertNull(DatasheetResourcingService.mentionOf(null, "text"));
        assertNull(DatasheetResourcingService.mentionOf("SN74LS174N", null));
        assertNull(DatasheetResourcingService.mentionOf("SN74LS174N", ""));
    }
}
