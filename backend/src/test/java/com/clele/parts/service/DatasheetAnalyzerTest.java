package com.clele.parts.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the routing decision in {@link DatasheetAnalyzer}. The thresholds there were calibrated on
 * only four real datasheets, so the cases that matter are the two that a character-count metric
 * gets wrong: a document whose text layer is packaging boilerplate with the parametric tables
 * pasted in as images, and one that is a pure scan.
 */
class DatasheetAnalyzerTest {

    private final DatasheetAnalyzer analyzer = new DatasheetAnalyzer();

    @Test
    void textLayerWithParametricHeadingsRoutesToText() {
        byte[] pdf = pdf("Absolute Maximum Ratings",
                "VCC Supply voltage 7 V",
                "DC Characteristics",
                "IOL Low-level output current 8 mA");

        DatasheetAnalyzer.Analysis a = analyzer.analyze(pdf);

        assertEquals(DatasheetAnalyzer.Route.TEXT, a.route());
        assertFalse(a.needsVision());
        assertTrue(a.headings().contains("absolute maximum"), a.headings().toString());
        assertTrue(a.headings().contains("dc characteristic"), a.headings().toString());
    }

    /**
     * The TI SN74LS174 case: a healthy-looking text layer (title block, notes, packaging tables)
     * that contains no parametric section at all, because those pages are scanned images. A
     * chars-per-page metric scores this the same as a good datasheet — the heading test is what
     * catches it.
     */
    @Test
    void textLayerWithoutParametricHeadingsRoutesToVision() {
        byte[] pdf = pdf("PRODUCTION DATA information is current as of publication date.",
                "Lead finish/Ball material: Parts may have multiple material finish options.",
                "Finish values may wrap to two lines if the value exceeds the maximum width.",
                "Ordering Information and Mechanical Data");

        DatasheetAnalyzer.Analysis a = analyzer.analyze(pdf);

        assertEquals(DatasheetAnalyzer.Route.IMAGE_TABLES, a.route());
        assertTrue(a.needsVision());
        assertTrue(a.textChars() > 0, "the boilerplate text layer should still be extracted");
        assertEquals(0, a.headingHits(),
                "\"exceeds the maximum\" must not score as a parametric heading");
    }

    @Test
    void pdfWithNoTextAtAllRoutesToVision() {
        byte[] pdf = pdf();

        DatasheetAnalyzer.Analysis a = analyzer.analyze(pdf);

        assertEquals(DatasheetAnalyzer.Route.NO_TEXT_LAYER, a.route());
        assertTrue(a.needsVision());
        assertEquals(0, a.textChars());
    }

    @Test
    void htmlErrorPageIsUnusable() {
        DatasheetAnalyzer.Analysis a =
                analyzer.analyze("<html><body>404 Not Found</body></html>".getBytes());

        assertEquals(DatasheetAnalyzer.Route.UNUSABLE, a.route());
        assertFalse(a.usable());
        assertTrue(a.error().contains("not a PDF"), a.error());
    }

    @Test
    void emptyResponseIsUnusable() {
        assertEquals(DatasheetAnalyzer.Route.UNUSABLE, analyzer.analyze(new byte[0]).route());
        assertEquals(DatasheetAnalyzer.Route.UNUSABLE, analyzer.analyze(null).route());
    }

    @Test
    void truncatedPdfIsUnusableRatherThanThrowing() {
        byte[] good = pdf("Absolute Maximum Ratings");
        byte[] truncated = new byte[good.length / 2];
        System.arraycopy(good, 0, truncated, 0, truncated.length);

        DatasheetAnalyzer.Analysis a = analyzer.analyze(truncated);

        assertEquals(DatasheetAnalyzer.Route.UNUSABLE, a.route());
        assertFalse(a.usable());
    }

    /** A one-page PDF containing the given lines; no lines means a genuinely blank page. */
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
            throw new IllegalStateException("could not build test PDF", e);
        }
    }
}
