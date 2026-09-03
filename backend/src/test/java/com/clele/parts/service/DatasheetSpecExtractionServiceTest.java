package com.clele.parts.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins {@link DatasheetSpecExtractionService#buildExcerpt}, which is the only part of the extraction
 * with real logic and no network: what it decides to send is what the feature costs and what the
 * model gets to see. All of it is constructed from page text, so none of these need a PDF.
 */
class DatasheetSpecExtractionServiceTest {

    /** Built with nulls: buildExcerpt touches no collaborator. */
    private final DatasheetSpecExtractionService service =
            new DatasheetSpecExtractionService(null, null, null, null, null, null, null, null);

    private static String page(String body, int filler) {
        return body + "\n" + "x ".repeat(filler);
    }

    @Test
    void sendsTheFrontMatterEvenWithNoParametricSection() {
        // A datasheet whose specification tables are images has no heading to cut around, but its
        // first page is real text and is where the functional description lives. Sending nothing at
        // all would make the IMAGE_TABLES route useless rather than merely thin.
        String excerpt = service.buildExcerpt(List.of(
                page("HEX D-TYPE FLIP-FLOP with a long feature list", 2000),
                page("pinout drawing", 10),
                page("package outline", 10)));

        assertThat(excerpt).contains("[page 1]", "HEX D-TYPE FLIP-FLOP");
        assertThat(excerpt).doesNotContain("package outline");
    }

    @Test
    void addsTheSecondPageWhenTheFirstIsOnlyATitleBlock() {
        // Some vendors put nothing but the title and an ordering code on page 1. Stopping there
        // would send a page that describes nothing.
        String excerpt = service.buildExcerpt(List.of(
                "AT28C16\n",
                page("Description: a 16K EEPROM organised as 2048 words by 8 bits", 100),
                page("unrelated later page", 10)));

        assertThat(excerpt).contains("2048 words");
        assertThat(excerpt).doesNotContain("unrelated later page");
    }

    @Test
    void includesTheTextAroundEachParametricHeading() {
        String excerpt = service.buildExcerpt(List.of(
                page("title", 2000),
                page("pinout only", 10),
                "ABSOLUTE MAXIMUM RATINGS\nSupply voltage 7 V\n",
                page("mechanical drawing", 10)));

        assertThat(excerpt).contains("Supply voltage 7 V");
        // The window reaches forward from the heading, so a following page is carried in too — that
        // is deliberate, tables run over page breaks.
        assertThat(excerpt).contains("[page 3]");
        // Page 2 sits between the front matter and the heading and is not worth paying for.
        assertThat(excerpt).doesNotContain("pinout only");
    }

    @Test
    void mergesOverlappingHeadingWindowsInsteadOfRepeatingTheText() {
        // Datasheets stack subsections ("DC Characteristics", "AC Characteristics") a paragraph
        // apart. Emitting a window per heading would send the same span several times over, which
        // is exactly the cost this excerpting exists to avoid.
        String tail = "value tables ".repeat(50);
        String excerpt = service.buildExcerpt(List.of(
                page("title", 2000),
                "DC CHARACTERISTICS\nrows\nAC CHARACTERISTICS\n" + tail));

        assertThat(excerpt).containsOnlyOnce("DC CHARACTERISTICS");
        assertThat(excerpt).containsOnlyOnce("AC CHARACTERISTICS");
    }

    @Test
    void capsTheExcerptAndSaysItWasCut() {
        // A silently truncated excerpt would look like a document that simply had less in it.
        List<String> pages = new java.util.ArrayList<>();
        pages.add(page("title", 2000));
        for (int i = 0; i < 60; i++) {
            pages.add("ELECTRICAL CHARACTERISTICS\n" + "row of numbers ".repeat(400));
        }

        String excerpt = service.buildExcerpt(pages);

        assertThat(excerpt).contains("excerpt truncated");
        assertThat(excerpt.length()).isLessThan(120_000);
    }

    @Test
    void marksPagesSoAValueCanBeTracedBack() {
        String excerpt = service.buildExcerpt(List.of(
                page("title", 2000),
                page("filler", 2000),
                "DC CHARACTERISTICS\nVOH 2.4 V\n"));

        assertThat(excerpt).contains("[page 1]").contains("[page 3]");
    }

    @Test
    void handlesAnEmptyDocumentWithoutFailing() {
        assertThat(service.buildExcerpt(List.of())).isEmpty();
    }
}
