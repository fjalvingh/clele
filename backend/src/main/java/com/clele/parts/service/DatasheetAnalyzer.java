package com.clele.parts.service;

import lombok.Builder;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decides whether a datasheet PDF's specification data can be reached by text extraction, or
 * whether it needs the vision path (rendering the pages and reading them as images).
 *
 * <p><b>Why the test is "does a parametric heading appear in the text layer" and not a
 * statistical one.</b> The obvious metric — characters per page — does not work, because the
 * failure mode in this catalogue is the <em>hybrid</em> PDF: a modern text layer carrying the
 * title block, notes and packaging/ordering tables, with every parametric table pasted in as a
 * scanned image. Measured on four real datasheets from this database:
 *
 * <pre>
 *   Atmel AT28C16   (specs in text)    901 avg chars/page, 16% of pages under 200 chars
 *   TI SN74LS174    (specs as images)  991 avg chars/page, 18% of pages under 200 chars
 * </pre>
 *
 * The two are indistinguishable by volume; only the fully-scanned Signetics 82S101 (0 chars on
 * every page) separates out. What does separate them is whether the extracted text actually
 * contains a recognisable parametric section heading — "Absolute Maximum Ratings", "Limiting
 * Values", "DC Characteristics" and friends. On the same four documents that test scored 5, 10,
 * 0 and 0 hits respectively, correctly routing the TI hybrid to the vision path despite its
 * healthy-looking character count.
 *
 * <p>{@link #SECTION_HEADINGS} is therefore the calibration surface of this class. The phrases
 * are deliberately whole ("maximum rating", not "maximum") so that prose such as "exceeds the
 * maximum" in a packaging note does not score a hit — that exact string appears in the TI
 * document and correctly does not match. Extend the list when a vendor's wording is found to be
 * misrouted; the preflight report prints the matched headings per part so misses are visible.
 */
@Slf4j
@Service
public class DatasheetAnalyzer {

    /**
     * Section headings that indicate the text layer carries the parametric tables. Whole phrases
     * only — see the class comment. Matched case-insensitively.
     */
    private static final List<String> SECTION_HEADINGS = List.of(
            "absolute maximum",
            "maximum rating",
            "limiting value",
            "quick reference data",
            "electrical characteristic",
            "dc characteristic",
            "ac characteristic",
            "dc electrical characteristic",
            "ac electrical characteristic",
            "recommended operating",
            "operating condition",
            "thermal characteristic",
            "thermal resistance",
            "switching characteristic",
            "timing characteristic",
            "dc parameter",
            "static characteristic",
            "dynamic characteristic");

    private static final Pattern HEADING_PATTERN = Pattern.compile(
            SECTION_HEADINGS.stream().map(Pattern::quote).reduce((a, b) -> a + "|" + b).orElseThrow(),
            Pattern.CASE_INSENSITIVE);

    /** How the specs in this document can be reached. */
    public enum Route {
        /** Parametric headings found in the text layer — text extraction will yield specs. */
        TEXT,
        /**
         * A text layer exists but carries no parametric heading: the title block and packaging
         * tables are text while the specification tables are images. Needs the vision path.
         */
        IMAGE_TABLES,
        /** No extractable text at all — a pure scan. Needs the vision path. */
        NO_TEXT_LAYER,
        /** Not analysable: see {@link Analysis#error()}. */
        UNUSABLE
    }

    @Builder
    public record Analysis(
            Route route,
            int pages,
            int textChars,
            int headingHits,
            Set<String> headings,
            String error) {

        public boolean usable() {
            return route != Route.UNUSABLE;
        }

        /** True when the specs are only reachable by reading rendered pages as images. */
        public boolean needsVision() {
            return route == Route.IMAGE_TABLES || route == Route.NO_TEXT_LAYER;
        }
    }

    /** Analyse raw downloaded bytes. Never throws — a failure comes back as {@link Route#UNUSABLE}. */
    public Analysis analyze(byte[] data) {
        if (data == null || data.length == 0) {
            return failed("empty response body");
        }
        if (!looksLikePdf(data)) {
            return failed("not a PDF (does not start with %PDF) — probably an HTML error page");
        }

        try (PDDocument doc = Loader.loadPDF(data)) {
            int pages = doc.getNumberOfPages();
            if (pages == 0) {
                return failed("PDF has no pages");
            }

            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            String text = stripper.getText(doc);

            int textChars = text.replaceAll("\\s", "").length();
            Set<String> matched = matchedHeadings(text);

            Route route;
            if (textChars == 0) {
                route = Route.NO_TEXT_LAYER;
            } else if (matched.isEmpty()) {
                route = Route.IMAGE_TABLES;
            } else {
                route = Route.TEXT;
            }

            return Analysis.builder()
                    .route(route)
                    .pages(pages)
                    .textChars(textChars)
                    .headingHits(countHits(text))
                    .headings(matched)
                    .build();
        } catch (Exception e) {
            // PDFBox throws a range of IOException subtypes plus the odd RuntimeException on
            // malformed files; none of them should abort a bulk run.
            return failed("could not parse PDF: " + e.getMessage());
        }
    }

    private static boolean looksLikePdf(byte[] data) {
        return com.clele.parts.util.PdfBytes.looksLikePdf(data);
    }

    /** The distinct headings present, lower-cased, in the order the pattern lists them. */
    private static Set<String> matchedHeadings(String text) {
        Set<String> found = new LinkedHashSet<>();
        Matcher m = HEADING_PATTERN.matcher(text);
        while (m.find()) {
            found.add(m.group().toLowerCase());
        }
        return found;
    }

    private static int countHits(String text) {
        Matcher m = HEADING_PATTERN.matcher(text);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    private static Analysis failed(String error) {
        return Analysis.builder()
                .route(Route.UNUSABLE)
                .headings(Set.of())
                .error(error)
                .build();
    }
}
