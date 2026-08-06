package com.clele.parts.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The stem list is a set of guesses verified downstream, so the property that matters is that the
 * <em>correct</em> stem appears somewhere in it — each case below is a real part from this
 * catalogue paired with the stem ti.com was confirmed to serve.
 */
class VendorDatasheetUrlsTest {

    @Test
    void producesTheStemTiActuallyServes() {
        assertContains("SN74LS30N", "sn74ls30");
        assertContains("SN74LS155AN", "sn74ls155a");   // keeps the A revision, drops the package
        assertContains("TLC274CN", "tlc274");          // temperature grade + package
        assertContains("LM324PWR", "lm324");           // package + reel
        assertContains("SN74LVTH541DWR", "sn74lvth541");
        assertContains("CD74HCT174E", "cd74hct174");
    }

    /** "/NOPB" is an ordering suffix, not part of the number. */
    @Test
    void dropsEverythingAfterASlash() {
        assertContains("LM1117DT-2.5/NOPB", "lm1117");
    }

    /** TI files its military SNJ parts under the commercial SN number. */
    @Test
    void mapsTheMilitaryPrefix() {
        assertContains("SNJ54LS125AJ", "sn54ls125a");
    }

    @Test
    void stopsBeforeStemsBecomeMeaningless() {
        assertTrue(VendorDatasheetUrls.stems("SN74LS30N").stream().allMatch(s -> s.length() >= 5));
    }

    @Test
    void isBestGuessFirst() {
        assertEquals("sn74ls30n", VendorDatasheetUrls.stems("SN74LS30N").get(0));
    }

    private static void assertContains(String partNumber, String expectedStem) {
        List<String> stems = VendorDatasheetUrls.stems(partNumber);
        assertTrue(stems.contains(expectedStem),
                partNumber + " -> " + stems + " should contain " + expectedStem);
    }
}
