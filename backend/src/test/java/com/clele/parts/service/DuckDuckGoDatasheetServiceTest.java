package com.clele.parts.service;

import com.clele.parts.service.DuckDuckGoDatasheetService.SearchStatus;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the difference between "searched and found nothing" and "was never allowed to search".
 *
 * <p>DuckDuckGo serves its bot challenge with HTTP <b>202</b> — a success status — so the scraper
 * parsed it, found no results and reported an empty search. Every part then looked like a part with
 * no datasheet in existence. The fixtures here are the real pages: {@code challenge-202.html} is what
 * html.duckduckgo.com returned to a {@code curl} user-agent, {@code results-200.html} a trimmed real
 * result page.
 */
class DuckDuckGoDatasheetServiceTest {

    @Test
    void recognisesTheBotChallengeDespiteItsSuccessStatus() throws IOException {
        assertEquals(SearchStatus.BLOCKED,
                DuckDuckGoDatasheetService.classify(202, fixture("challenge-202.html")));
    }

    @Test
    void recognisesARealResultPage() throws IOException {
        assertEquals(SearchStatus.OK,
                DuckDuckGoDatasheetService.classify(200, fixture("results-200.html")));
    }

    /** A genuinely empty search says so, and must stay distinguishable from a refusal. */
    @Test
    void reportsAnEmptyResultPageAsNoResults() {
        assertEquals(SearchStatus.NO_RESULTS,
                DuckDuckGoDatasheetService.classify(200,
                        "<html><body><div class=\"no-results\">No results found.</div></body></html>"));
    }

    /** 403/429 arrive as exceptions, but a body-bearing refusal must not be read as an empty search. */
    @Test
    void treatsANonOkStatusWithoutResultsAsBlocked() {
        assertEquals(SearchStatus.BLOCKED,
                DuckDuckGoDatasheetService.classify(202, "<html><body>please try again later</body></html>"));
    }

    /**
     * The failure mode this whole class exists for: a page we cannot parse is reported as a refusal,
     * never as "this part has no datasheet". Guessing the optimistic reading is what caused the bug.
     */
    @Test
    void treatsAnUnrecognisedPageAsBlockedRatherThanEmpty() {
        assertEquals(SearchStatus.BLOCKED,
                DuckDuckGoDatasheetService.classify(200, "<html><body><h1>Something else entirely</h1></body></html>"));
    }

    @Test
    void treatsAnEmptyBodyAsAFailure() {
        assertEquals(SearchStatus.FAILED, DuckDuckGoDatasheetService.classify(200, null));
        assertEquals(SearchStatus.FAILED, DuckDuckGoDatasheetService.classify(200, "   "));
    }

    private static String fixture(String name) throws IOException {
        try (InputStream in = DuckDuckGoDatasheetServiceTest.class.getResourceAsStream("/ddg/" + name)) {
            if (in == null) throw new IllegalStateException("missing fixture /ddg/" + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
