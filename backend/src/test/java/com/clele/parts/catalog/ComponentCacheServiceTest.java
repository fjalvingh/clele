package com.clele.parts.catalog;

import com.clele.parts.catalog.ComponentCacheRepository.CcAttribute;
import com.clele.parts.catalog.ComponentCacheRepository.CcComponent;
import com.clele.parts.dto.ComponentCacheDetailDTO;
import com.clele.parts.model.SpecDefinition;
import com.clele.parts.repository.SpecAliasRepository;
import com.clele.parts.repository.SpecDefinitionRepository;
import com.clele.parts.service.CurrentOrganisationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins how a cached attribute becomes a stored spec value.
 *
 * <p>Every case here is one where a plausible alternative silently stores a wrong number. The cache
 * holds SI base units and this app's fields declare their own, so the failure mode is never an
 * exception — it is 0.0016 sitting in a field that means millimetres, or 1 recorded as a supply
 * voltage because the vendor listed the negative rail second. Those are indistinguishable from
 * correct data once written, which is why the rules are pinned rather than left to review.
 */
class ComponentCacheServiceTest {

    private static final Long ORG_ID = 3L;

    private ComponentCacheRepository repository;
    private SpecDefinitionRepository specDefinitionRepository;
    private ComponentCacheService service;
    private List<SpecDefinition> definitions;

    @BeforeEach
    void setUp() {
        repository = mock(ComponentCacheRepository.class);
        specDefinitionRepository = mock(SpecDefinitionRepository.class);
        SpecAliasRepository aliasRepository = mock(SpecAliasRepository.class);
        CurrentOrganisationService organisations = mock(CurrentOrganisationService.class);

        definitions = new ArrayList<>();
        when(organisations.currentId()).thenReturn(ORG_ID);
        when(specDefinitionRepository.findByOrganisationIdOrderByDisplayOrderAscNameAsc(anyLong()))
                .thenReturn(definitions);
        when(aliasRepository.findByOrganisationIdOrderByJsonNameAsc(anyLong())).thenReturn(List.of());
        when(repository.available()).thenReturn(true);
        when(repository.findByLcsc(anyString())).thenReturn(java.util.Optional.of(component()));

        service = new ComponentCacheService(repository, specDefinitionRepository, aliasRepository,
                organisations);
    }

    @Test
    @DisplayName("an attribute with no matching field keeps the readable string under a derived key")
    void unknownAttributeKeepsDisplay() {
        givenAttributes(numeric("Gain Bandwidth Product", "frequency", "2.2 MHz", 2.2e6, 1));

        ComponentCacheDetailDTO detail = service.load("C1");

        // Squashed from the source name — the shape every key in this app already has, so a later
        // "rescan from parts" can promote it into a real field.
        assertEquals("2.2 MHz", detail.getSpecs().get("gainbandwidthproduct"));
        assertFalse(detail.getAttributes().get(0).isKnown());
    }

    @Test
    @DisplayName("a NUMBER field storing base SI units takes the bare number")
    void numberFieldInBaseUnitTakesTheNumber() {
        given(number("supplyvoltage", "V"));
        givenAttributes(numeric("Supply Voltage", "voltage", "5.5 V", 5.5, 1));

        assertEquals("5.5", service.load("C1").getSpecs().get("supplyvoltage"));
    }

    @Test
    @DisplayName("a NUMBER field declaring a prefixed unit is converted into it, not left in SI")
    void prefixedUnitIsConverted() {
        given(number("length", "mm"));
        givenAttributes(numeric("Length", "length", "1.6 mm", 0.0016, 1));

        // 0.0016 m is 1.6 mm. Storing the SI figure against a field that means millimetres would be
        // wrong by a factor of a thousand and look entirely plausible.
        assertEquals("1.6", service.load("C1").getSpecs().get("length"));
    }

    @Test
    @DisplayName("a NUMBER field whose unit does not reconcile falls back to the readable string")
    void irreconcilableUnitFallsBackToDisplay() {
        given(number("ratedvoltage", "°C"));   // nonsense pairing, but the guard must hold
        givenAttributes(numeric("Rated Voltage", "voltage", "16 V", 16.0, 1));

        assertEquals("16 V", service.load("C1").getSpecs().get("ratedvoltage"));
    }

    @Test
    @DisplayName("a NUMBER field with no declared unit takes a bare number only from a scale-free family")
    void unitlessFieldOnlyTakesScaleFreeNumbers() {
        given(number("numberofio", null));
        given(number("operatingvoltagerange", null));
        givenAttributes(
                numeric("Number of I/O", "count", "6", 6.0, 1),
                numeric("Operating Voltage Range", "voltage", "2.7 V", 2.7, 1));

        var specs = service.load("C1").getSpecs();
        // A count means the same thing with or without a unit.
        assertEquals("6", specs.get("numberofio"));
        // A voltage does not: 2.7 alone could be volts or millivolts, and nothing here records which.
        assertEquals("2.7 V", specs.get("operatingvoltagerange"));
    }

    @Test
    @DisplayName("a multi-slot value never yields a number — its value_num is positional, not semantic")
    void multiSlotValueKeepsTheRange() {
        given(number("operatingtemperature", "°C"));
        givenAttributes(numeric("Operating Temperature", "temperature", "-40.0 °C ~ 125.0 °C", -40.0, 2));

        // -40 is only "whichever end the vendor listed first". Recording it as the operating
        // temperature would be a fact the user cannot see is missing its other half.
        assertEquals("-40.0 °C ~ 125.0 °C", service.load("C1").getSpecs().get("operatingtemperature"));
    }

    @Test
    @DisplayName("absent values are dropped, not stored as \"-\" or \"NaN\"")
    void absentValuesAreSkipped() {
        givenAttributes(
                new CcAttribute("I2C", "count", "-", null, null, "NaN", 1),
                new CcAttribute("Real-Time Clock", "string", "-", null, null, "-", 1));

        ComponentCacheDetailDTO detail = service.load("C1");

        assertTrue(detail.getSpecs().isEmpty(), detail.getSpecs().toString());
        assertEquals(List.of("I2C", "Real-Time Clock"), detail.getSkipped());
    }

    @Test
    @DisplayName("attributes that are already columns do not arrive again as specifications")
    void columnAttributesAreNotDuplicatedIntoSpecs() {
        givenAttributes(
                new CcAttribute("Manufacturer", "identifier", "Microchip Tech", null, null, "Microchip Tech", 1),
                new CcAttribute("Package", "identifier", "SOIC-8", null, null, "SOIC-8", 1),
                new CcAttribute("Status", "identifier", "Active", null, null, "Active", 1),
                new CcAttribute("CPU Core", "identifier", "AVR", null, null, "AVR", 1));

        ComponentCacheDetailDTO detail = service.load("C1");

        // The key is squashed; the value is the vendor's, untouched.
        assertEquals("AVR", detail.getSpecs().get("cpucore"));
        assertNull(detail.getSpecs().get("package"));
        assertEquals("SOIC-8", detail.getFootprint());
        assertEquals("Microchip Tech", detail.getManufacturer());
    }

    @Test
    @DisplayName("a textual attribute takes display, which carries every slot, not just the first")
    void textualAttributeTakesTheWholeList() {
        givenAttributes(new CcAttribute("Peripheral/Function", "identifier",
                "Watchdog, LIN, IrDA", null, null, "Watchdog", 1));

        assertEquals("Watchdog, LIN, IrDA", service.load("C1").getSpecs().get("peripheralfunction"));
    }

    @Test
    @DisplayName("a BOOLEAN field normalises the vendor's Yes/No")
    void booleanFieldIsNormalised() {
        given(SpecDefinition.builder().jsonName("internaloscillator").name("Internal Oscillator")
                .dataType("BOOLEAN").build());
        givenAttributes(new CcAttribute("Internal Oscillator", "string", "Yes", null, null, "Yes", 1));

        assertEquals("true", service.load("C1").getSpecs().get("internaloscillator"));
    }

    @Test
    @DisplayName("a search term too short to form a trigram is refused rather than answered badly")
    void shortTermsAreNotSearched() {
        assertTrue(service.search("LM").isEmpty());
        assertTrue(service.search(null).isEmpty());
    }

    // ── Fixtures ─────────────────────────────────────────────────────────────

    private void given(SpecDefinition def) {
        definitions.add(def);
    }

    private static SpecDefinition number(String jsonName, String unit) {
        return SpecDefinition.builder().jsonName(jsonName).name(jsonName).dataType("NUMBER")
                .unit(unit).build();
    }

    private void givenAttributes(CcAttribute... attributes) {
        when(repository.attributes(anyString())).thenReturn(List.of(attributes));
    }

    private static CcAttribute numeric(String name, String family, String display, double value, int slots) {
        return new CcAttribute(name, family, display, value, BigDecimal.valueOf(value), null, slots);
    }

    private static CcComponent component() {
        return new CcComponent("C1", "ATTINY402-SSFR", "Microchip Tech", "8-bit MCU",
                "SOIC-8", "Extended", "Active", "Embedded Processors and Controllers",
                "Microcontroller Units (MCUs/MPUs/SOCs)", 816, 8,
                new BigDecimal("0.6"), new BigDecimal("0.5"), "https://example.invalid/ds.pdf",
                "https://example.invalid/img.png", "https://example.invalid/p", 1.0);
    }
}
