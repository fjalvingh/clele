package com.clele.parts.service.bom;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BomColumnMapperTest {

    private final BomColumnMapper mapper = new BomColumnMapper();

    @Test
    @DisplayName("a KiCad 8 grouped BOM export maps without help")
    void mapsKicadExport() {
        Map<BomColumnRole, String> mapping = mapper.detect(List.of(
                "Reference", "Value", "Datasheet", "Footprint", "Qty", "DNP"));

        assertEquals("Reference", mapping.get(BomColumnRole.REFERENCES));
        assertEquals("Value", mapping.get(BomColumnRole.VALUE));
        assertEquals("Footprint", mapping.get(BomColumnRole.FOOTPRINT));
        assertEquals("Qty", mapping.get(BomColumnRole.QUANTITY));
        assertEquals("Datasheet", mapping.get(BomColumnRole.DATASHEET));
        assertEquals("DNP", mapping.get(BomColumnRole.DNP));
    }

    @Test
    @DisplayName("KiCad's legacy grouped-CSV headers map too")
    void mapsLegacyKicadExport() {
        Map<BomColumnRole, String> mapping = mapper.detect(List.of(
                "Ref", "Qnty", "Value", "Cmp name", "Footprint", "Description", "Vendor"));

        assertEquals("Ref", mapping.get(BomColumnRole.REFERENCES));
        assertEquals("Qnty", mapping.get(BomColumnRole.QUANTITY));
        assertEquals("Value", mapping.get(BomColumnRole.VALUE));
        assertEquals("Description", mapping.get(BomColumnRole.DESCRIPTION));
    }

    @Test
    @DisplayName("a distributor column is not mistaken for the manufacturer")
    void doesNotTreatSupplierAsManufacturer() {
        // "JLCPCB" is who you buy from, not who made the part. Guessing this wrong fills the
        // manufacturer of every line on the board with a distributor's name, and nothing says so.
        Map<BomColumnRole, String> mapping = mapper.detect(List.of(
                "Reference", "Value", "Supplier", "Vendor"));

        assertNull(mapping.get(BomColumnRole.MANUFACTURER));
    }

    @Test
    @DisplayName("punctuation and case in a header are ignored, so vendor exports map as well")
    void ignoresPunctuationAndCase() {
        Map<BomColumnRole, String> mapping = mapper.detect(List.of(
                "Designator", "Comment", "Mfr. Part #", "MANUFACTURER", "Package"));

        assertEquals("Designator", mapping.get(BomColumnRole.REFERENCES));
        assertEquals("Comment", mapping.get(BomColumnRole.VALUE));
        assertEquals("Mfr. Part #", mapping.get(BomColumnRole.MPN));
        assertEquals("MANUFACTURER", mapping.get(BomColumnRole.MANUFACTURER));
        assertEquals("Package", mapping.get(BomColumnRole.FOOTPRINT));
    }

    @Test
    @DisplayName("a column is claimed by one role only, so MPN does not also become the value")
    void claimsEachColumnOnce() {
        Map<BomColumnRole, String> mapping = mapper.detect(List.of(
                "Reference", "Value", "Part Number", "Manufacturer"));

        assertEquals("Part Number", mapping.get(BomColumnRole.MPN));
        assertEquals("Value", mapping.get(BomColumnRole.VALUE));
        assertNotEquals(mapping.get(BomColumnRole.MPN), mapping.get(BomColumnRole.VALUE));
    }

    @Test
    @DisplayName("unrecognised headers are simply left out for the user to map")
    void leavesUnknownHeadersUnmapped() {
        Map<BomColumnRole, String> mapping = mapper.detect(List.of(
                "Reference", "Bestellnummer", "Lieferant"));

        assertEquals("Reference", mapping.get(BomColumnRole.REFERENCES));
        assertEquals(1, mapping.size(), "nothing should be guessed for the German headers");
    }

    @Test
    @DisplayName("DNP values read as booleans, and an inverted 'Fitted' column reads the other way")
    void readsDnpValues() {
        assertTrue(mapper.isDoNotPopulate("DNP", "yes"));
        assertTrue(mapper.isDoNotPopulate("DNP", "X"));
        assertTrue(mapper.isDoNotPopulate("DNP", "1"));
        assertFalse(mapper.isDoNotPopulate("DNP", ""));
        assertFalse(mapper.isDoNotPopulate("DNP", null));

        // "Fitted = no" and "DNP = yes" say the same thing; reading the header the same way round
        // would exclude precisely the parts that are fitted.
        assertTrue(mapper.isDoNotPopulate("Fitted", "no"));
        assertFalse(mapper.isDoNotPopulate("Fitted", "yes"));
    }

    @Test
    @DisplayName("an unrecognised DNP value counts as fitted — an import must not silently drop lines")
    void unknownDnpValueMeansFitted() {
        assertFalse(mapper.isDoNotPopulate("DNP", "maybe"));
        assertFalse(mapper.isDoNotPopulate("DNP", "-"));
    }
}
