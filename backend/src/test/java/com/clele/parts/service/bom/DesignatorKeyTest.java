package com.clele.parts.service.bom;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DesignatorKeyTest {

    @Test
    @DisplayName("designators normalise to one key regardless of order, case or separator")
    void normalisesToStableKey() {
        // This is what carries a confirmed match across a schematic revision: the same three caps
        // written three different ways must produce the same key, or the re-import sees them as
        // new lines and every match on them is lost.
        String expected = "C1,C2,C3";
        assertEquals(expected, DesignatorKey.normalize("C1,C2,C3"));
        assertEquals(expected, DesignatorKey.normalize("C3, C1, C2"));
        assertEquals(expected, DesignatorKey.normalize("c1 c2 c3"));
        assertEquals(expected, DesignatorKey.normalize("C1;C2;C3"));
        assertEquals(expected, DesignatorKey.normalize("  C2,C1 , C3  "));
    }

    @Test
    @DisplayName("sorting is natural — C2 before C10, not after it")
    void sortsNaturally() {
        assertEquals("C1,C2,C10", DesignatorKey.normalize("C10,C2,C1"));
        assertEquals(List.of("R2", "R11", "R100"), DesignatorKey.split("R100 R11 R2"));
    }

    @Test
    @DisplayName("different prefixes stay grouped by prefix")
    void groupsByPrefix() {
        assertEquals("C1,C2,R1,U3", DesignatorKey.normalize("U3,R1,C2,C1"));
    }

    @Test
    @DisplayName("blank input yields a blank key rather than throwing")
    void toleratesBlank() {
        assertEquals("", DesignatorKey.normalize(null));
        assertEquals("", DesignatorKey.normalize("   "));
        assertTrue(DesignatorKey.split(null).isEmpty());
    }

    @Test
    @DisplayName("repeated designators collapse — a line covers each part once")
    void dedupes() {
        assertEquals("C1,C2", DesignatorKey.normalize("C1,C2,C1"));
        assertEquals(2, DesignatorKey.count("C1,C2,C1"));
    }

    @Test
    @DisplayName("the count stands in for a missing quantity column")
    void countsDesignators() {
        assertEquals(3, DesignatorKey.count("C1,C2,C3"));
        assertEquals(1, DesignatorKey.count("U1"));
        assertEquals(0, DesignatorKey.count(""));
    }

    @Test
    @DisplayName("designators with no number at all still sort deterministically")
    void handlesUnnumbered() {
        assertEquals("MH,TP1", DesignatorKey.normalize("TP1, MH"));
    }
}
