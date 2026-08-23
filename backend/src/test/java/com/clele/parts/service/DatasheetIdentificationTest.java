package com.clele.parts.service;

import com.clele.parts.dto.PartSearchResultDTO;
import com.clele.parts.repository.PartAttachmentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Identifying a part from an uploaded datasheet — the Quick Add path where the document is all
 * there is.
 *
 * <p>What is pinned here is the shape of the answer and the two refusals. The mapping matters
 * because this result is handed to the same confirm form a web search feeds: specs arrive from the
 * model as {@code {key, value, page}} objects and have to leave as {@code "key: value"} strings, and
 * a mismatch there shows up as a part saved with no specifications rather than as an error. The
 * refusals matter because both alternatives are worse than a message — a document that named no
 * part would be saved as a part called nothing, and a scan would be charged for before being found
 * unreadable.
 */
class DatasheetIdentificationTest {

    private final RestTemplate restTemplate = mock(RestTemplate.class);
    private final SpecFieldCatalog specFieldCatalog = mock(SpecFieldCatalog.class);
    private final SpecDefinitionService specDefinitionService = mock(SpecDefinitionService.class);

    private DatasheetSpecExtractionService service() {
        DatasheetSpecExtractionService service = new DatasheetSpecExtractionService(
                mock(PartService.class), mock(PartAttachmentRepository.class),
                new DatasheetAnalyzer(), specDefinitionService, specFieldCatalog,
                new ObjectMapper(), restTemplate);
        ReflectionTestUtils.setField(service, "apiKey", "test-key");
        ReflectionTestUtils.setField(service, "model", "claude-haiku-4-5-20251001");
        when(specFieldCatalog.render())
                .thenReturn(new SpecFieldCatalog.Fields("\n  - \"supply_voltage_max\" (Supply voltage max)", 1));
        // The alias table is exercised by its own tests; here it must simply not lose a key.
        when(specDefinitionService.canonicalizeKeys(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        return service;
    }

    private void modelAnswers(String json) {
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.POST), any(), eq(String.class)))
                .thenReturn(ResponseEntity.ok("""
                        {"content": [{"type": "text", "text": %s}],
                         "usage": {"input_tokens": 20000, "output_tokens": 300}}
                        """.formatted(quote(json))));
    }

    private static String quote(String s) {
        try {
            return new ObjectMapper().writeValueAsString(s);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void mapsTheDocumentOntoTheSameResultAWebSearchProduces() {
        modelAnswers("""
                {"mpn": "NE555", "manufacturer": "Texas Instruments",
                 "shortDescription": "Precision timer",
                 "category": "Timers", "details": "A timer that does timing things.",
                 "specs": [{"key": "supply_voltage_max", "value": "16", "page": 2}]}
                """);

        PartSearchResultDTO result = service().identify(datasheet(), "NE555.pdf");

        assertEquals("NE555", result.getMpn());
        assertEquals("Texas Instruments", result.getManufacturer());
        assertEquals("Precision timer", result.getShortDescription());
        assertEquals("A timer that does timing things.", result.getDetails());
        assertEquals(List.of("supply_voltage_max: 16"), result.getSpecs());
    }

    /** A part with no number is not a part; saying so beats creating one called nothing. */
    @Test
    void refusesADocumentThatNamesNoPart() {
        modelAnswers("""
                {"mpn": null, "details": null, "specs": []}
                """);

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().identify(datasheet(), "brochure.pdf"));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, e.getStatusCode());
        assertTrue(e.getReason().contains("Could not tell which component"), e.getReason());
    }

    /** Refused before the model call: a scan has no text to send, so there is nothing to pay for. */
    @Test
    void refusesAScanBeforeSpendingAnything() {
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().identify(blankPdf(), "scan.pdf"));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, e.getStatusCode());
        assertTrue(e.getReason().contains("no text layer"), e.getReason());
        org.mockito.Mockito.verifyNoInteractions(restTemplate);
    }

    @Test
    void refusesAFileThatIsNotAPdf() {
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().identify("<html>not a pdf</html>".getBytes(), "page.html"));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, e.getStatusCode());
    }

    /** A one-page document that reads as a datasheet: a title block and a parametric section. */
    private static byte[] datasheet() {
        return pdf("NE555 Precision Timer", "Texas Instruments",
                "Absolute Maximum Ratings", "VCC Supply voltage 16 V");
    }

    private static byte[] blankPdf() {
        return pdf();
    }

    private static byte[] pdf(String... lines) {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            if (lines.length > 0) {
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 11);
                    cs.newLineAtOffset(50, 700);
                    for (String line : lines) {
                        cs.showText(line);
                        cs.newLineAtOffset(0, -16);
                    }
                    cs.endText();
                }
            }
            doc.save(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
