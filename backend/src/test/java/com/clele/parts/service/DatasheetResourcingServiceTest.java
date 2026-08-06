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
    void ignoresCaseAndPunctuationWithinAWord() {
        assertEquals("MC1489P", DatasheetResourcingService.mentionOf("MC1489P.", "The MC-1489-P is a quad receiver"));
        assertEquals("BC856B", DatasheetResourcingService.mentionOf("bc856b", "TYPE: BC-856-B"));
    }

    /**
     * A part number spaced out across words is deliberately <em>not</em> matched. Joining across
     * whitespace is what let "SN7417" and a following "4" fuse into "SN74174"; refusing the odd
     * spaced-out heading is the price of never inventing a part number that isn't there.
     */
    @Test
    void doesNotJoinAcrossWhitespaceEvenWhenItWouldBeCorrect() {
        assertNull(DatasheetResourcingService.mentionOf("bc856b", "TYPE: BC 856 B"));
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

    /**
     * Regression: the version that trimmed by one character regardless of kind attached TI's SN7416
     * hex-inverter datasheet to four unrelated counters/shift registers, because "SN7416" is a
     * prefix of "SN74163" and appears 35 times in that document.
     */
    @Test
    void neverTrimsADigitToReachAMatch() {
        String sn7416Datasheet = "SN5406, SN5416, SN7406, SN7416 HEX INVERTER BUFFERS/DRIVERS";
        assertNull(DatasheetResourcingService.mentionOf("SN74163N", sn7416Datasheet));
        assertNull(DatasheetResourcingService.mentionOf("SN74164N", sn7416Datasheet));
        assertNull(DatasheetResourcingService.mentionOf("SN74165N", sn7416Datasheet));
        assertNull(DatasheetResourcingService.mentionOf("SN74161N", sn7416Datasheet));
        assertNull(DatasheetResourcingService.mentionOf("SN74174N", "SN5417, SN7417 HEX BUFFERS"));
    }

    /** The genuine part still matches in that same document. */
    @Test
    void stillMatchesThePartTheDatasheetIsActuallyFor() {
        assertEquals("SN7416",
                DatasheetResourcingService.mentionOf("SN7416N", "SN5406, SN5416, SN7406, SN7416 HEX INVERTER"));
    }

    /** Trailing letters are package/revision codes and still drop, longest match first. */
    @Test
    void stillTrimsTrailingLetters() {
        assertEquals("TL7712A", DatasheetResourcingService.mentionOf("TL7712AIP", "TL7712A supply voltage supervisor"));
        // "LM723C" is present, so the more specific form wins over the bare family name.
        assertEquals("LM723C", DatasheetResourcingService.mentionOf("LM723CN/NOPB", "LM723, LM723C voltage regulator"));
        assertEquals("LM723", DatasheetResourcingService.mentionOf("LM723CN/NOPB", "LM723 voltage regulator"));
    }

    /**
     * Regression: normalising the whole document into one punctuation-free string let words fuse
     * across line breaks. TI's SN7417 datasheet ends a line with "SN7417" and the next begins with
     * "4", which manufactured "SN74174" and attached the hex-buffer datasheet to a hex flip-flop.
     */
    @Test
    void doesNotFuseWordsAcrossLineBreaks() {
        String sn7417 = "SN5407, SN5417, SN7407, SN7417\n4 mA output current";
        assertNull(DatasheetResourcingService.mentionOf("SN74174N", sn7417));

        String sn7416 = "voltages of 30 V. The SN5416 and SN7416.\n1 of 8 pages";
        assertNull(DatasheetResourcingService.mentionOf("SN74161N", sn7416));
    }

    /** Punctuation inside a single word is still ignored, so a table's "MC14-89P" matches. */
    @Test
    void stillIgnoresPunctuationWithinAWord() {
        assertEquals("MC1489P", DatasheetResourcingService.mentionOf("MC1489P.", "TYPE MC14-89P quad receiver"));
    }

    /** A longer package suffix in the document is fine; a trailing digit is a different part. */
    @Test
    void allowsALongerSuffixButNotADifferentNumber() {
        assertEquals("SN74LS30", DatasheetResourcingService.mentionOf("SN74LS30", "SN54LS30, SN74LS30N gate"));
        assertNull(DatasheetResourcingService.mentionOf("SN7416N", "SN74163 synchronous counter"));
    }

    @Test
    void handlesMissingInput() {
        assertNull(DatasheetResourcingService.mentionOf(null, "text"));
        assertNull(DatasheetResourcingService.mentionOf("SN74LS174N", null));
        assertNull(DatasheetResourcingService.mentionOf("SN74LS174N", ""));
    }
}
