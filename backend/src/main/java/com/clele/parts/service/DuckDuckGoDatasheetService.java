package com.clele.parts.service;

import com.clele.parts.dto.DatasheetSuggestionDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Datasheet PDF search backed by DuckDuckGo's HTML (no-JS) search page.
 *
 * Flow: GET html.duckduckgo.com/html/?q=… and scrape result links + titles, walking a few result
 * pages (DDG's `s=` offset param). DuckDuckGo wraps outbound links behind a redirect
 * (`//duckduckgo.com/l/?uddg=<encoded-target>`); the real target is extracted from the `uddg` query
 * parameter. Because a `.pdf`-looking URL is often a dead link or a redirect to an HTML page (product
 * page, cookie wall, 404), every candidate is verified live (HEAD, falling back to a ranged GET
 * checking the `%PDF-` magic bytes) before being returned.
 *
 * <p><b>"Found nothing" and "was not allowed to look" are reported separately.</b> DuckDuckGo answers
 * an automated search with a bot challenge ("Select all squares containing a duck") served as HTTP
 * <b>202</b> — a success status, so a scraper parses it, finds no results and reports an empty search.
 * The two are indistinguishable to the caller unless the block is detected explicitly, which is what
 * {@link #classify} does; every result therefore carries a {@link SearchStatus} rather than being a
 * bare list.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DuckDuckGoDatasheetService {

    private static final String DDG_HTML = "https://html.duckduckgo.com/html/";

    private static final int MAX_PAGES = 4;
    private static final int PAGE_STEP = 30; // DDG's results-per-page for the html no-JS endpoint
    private static final int MAX_CANDIDATES = 24; // cap on verification HTTP calls per search
    private static final int TARGET_RESULTS = 8;
    private static final int VERIFY_POOL_SIZE = 6;

    private static final Pattern RESULT_PATTERN = Pattern.compile(
            "<a[^>]*class=\"result__a\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>",
            Pattern.DOTALL);
    private static final Pattern TAG_PATTERN = Pattern.compile("<[^>]+>");

    /**
     * Fingerprints of DuckDuckGo's bot challenge, taken from the page it actually serves (verified
     * 2026-08-07 by searching with a {@code curl} user-agent: HTTP 202, 14 KB, no results section).
     */
    private static final List<String> CHALLENGE_MARKERS = List.of(
            "anomaly-modal", "anomaly.js", "challenge-form", "bots use duckduckgo");

    /** How a search ended — an empty result list means nothing without this. */
    public enum SearchStatus {
        /** The search ran and returned usable links. */
        OK,
        /** The search ran and genuinely found nothing (or nothing that verified as a live PDF). */
        NO_RESULTS,
        /** DuckDuckGo refused to search: bot challenge, 403 or 429. Says nothing about the part. */
        BLOCKED,
        /** The request itself failed — network error, timeout, unexpected status. */
        FAILED
    }

    public record SearchResult(SearchStatus status, List<DatasheetSuggestionDTO> results, String detail) {
        public boolean blocked() {
            return status == SearchStatus.BLOCKED;
        }

        static SearchResult of(SearchStatus status, String detail) {
            return new SearchResult(status, List.of(), detail);
        }
    }

    /** One fetched result page: its status, the PDF links on it, and whether it held results at all. */
    private record Page(SearchStatus status, String detail, List<DatasheetSuggestionDTO> pdfs, boolean hadResults) {
        static Page failed(SearchStatus status, String detail) {
            return new Page(status, detail, List.of(), false);
        }
    }

    private final RestTemplate restTemplate;

    /** Short-timeout template dedicated to per-candidate liveness checks (many run in parallel). */
    private final RestTemplate verifyRestTemplate = buildVerifyRestTemplate();

    public SearchResult search(String query) {
        String q = query + " datasheet filetype:pdf";

        List<DatasheetSuggestionDTO> candidates = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        SearchStatus refusal = null;
        String refusalDetail = null;

        for (int page = 0; page < MAX_PAGES && candidates.size() < MAX_CANDIDATES; page++) {
            Page result = fetchPage(q, page * PAGE_STEP, seen);

            if (result.status() == SearchStatus.BLOCKED || result.status() == SearchStatus.FAILED) {
                log.warn("DuckDuckGo datasheet search for '{}' {} on page {}: {}",
                        query, result.status() == SearchStatus.BLOCKED ? "was blocked" : "failed",
                        page, result.detail());
                // Only fatal while we have nothing: being cut off on page 3 still leaves pages 1–2.
                if (candidates.isEmpty()) {
                    refusal = result.status();
                    refusalDetail = result.detail();
                }
                break;
            }

            candidates.addAll(result.pdfs());
            if (!result.hadResults()) {
                break; // ran out of result pages
            }
        }

        if (candidates.isEmpty()) {
            if (refusal != null) {
                return SearchResult.of(refusal, refusalDetail);
            }
            log.info("DuckDuckGo datasheet search for '{}' returned no candidate PDF links", query);
            return SearchResult.of(SearchStatus.NO_RESULTS, null);
        }

        List<DatasheetSuggestionDTO> verified = verifyAll(candidates);
        if (verified.isEmpty()) {
            log.warn("DuckDuckGo datasheet search for '{}' found {} pdf-suffixed candidates but none verified live",
                    query, candidates.size());
            return SearchResult.of(SearchStatus.NO_RESULTS,
                    candidates.size() + " candidate link(s) found, none served a live PDF");
        }
        return new SearchResult(SearchStatus.OK, verified, null);
    }

    private Page fetchPage(String q, int offset, Set<String> seen) {
        String url = DDG_HTML + "?q=" + encode(q) + (offset > 0 ? "&s=" + offset : "");

        HttpHeaders headers = new HttpHeaders();
        headers.set("User-Agent",
                "Mozilla/5.0 (X11; Linux x86_64; rv:124.0) Gecko/20100101 Firefox/124.0");
        headers.set("Accept-Language", "en-US,en;q=0.5");

        ResponseEntity<String> resp;
        try {
            resp = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), String.class);
        } catch (HttpStatusCodeException e) {
            int code = e.getStatusCode().value();
            // 403/429 are how a search engine says "not you", not how it says "nothing found".
            SearchStatus status = (code == 403 || code == 429) ? SearchStatus.BLOCKED : SearchStatus.FAILED;
            return Page.failed(status, "HTTP " + code);
        } catch (RestClientException e) {
            return Page.failed(SearchStatus.FAILED, e.getMessage());
        }

        String body = resp.getBody();
        SearchStatus status = classify(resp.getStatusCode().value(), body);
        if (status == SearchStatus.BLOCKED || status == SearchStatus.FAILED) {
            return Page.failed(status, detailFor(status, resp.getStatusCode().value(), body));
        }
        return new Page(status, null, parseResults(body, seen), status == SearchStatus.OK);
    }

    /**
     * Decides what a fetched page actually is. Package-private and static so the classification can be
     * pinned against the real challenge page without going near the network.
     *
     * <p>{@link SearchStatus#OK} here means "this page holds search results" — whether any of them are
     * PDFs is a separate question, answered by the caller.
     */
    static SearchStatus classify(int statusCode, String body) {
        if (body == null || body.isBlank()) {
            return SearchStatus.FAILED;
        }
        String lower = body.toLowerCase();
        for (String marker : CHALLENGE_MARKERS) {
            if (lower.contains(marker)) {
                return SearchStatus.BLOCKED;
            }
        }
        if (lower.contains("result__a")) {
            return SearchStatus.OK;
        }
        if (statusCode != 200) {
            // A results page is answered 200. Anything else with no results on it is a refusal —
            // the challenge is served as 202, a *success* status, which is what made it invisible.
            return SearchStatus.BLOCKED;
        }
        if (lower.contains("no-results") || lower.contains("no results found")) {
            return SearchStatus.NO_RESULTS;
        }
        // 200, no results section, no "no results" message: the page is not what we parse. Reporting
        // that as "this part has no datasheet" is exactly the lie this method exists to prevent.
        return SearchStatus.BLOCKED;
    }

    private static String detailFor(SearchStatus status, int statusCode, String body) {
        if (status == SearchStatus.FAILED) {
            return "empty response (HTTP " + statusCode + ")";
        }
        String lower = body == null ? "" : body.toLowerCase();
        boolean challenge = CHALLENGE_MARKERS.stream().anyMatch(lower::contains);
        return challenge
                ? "bot challenge served as HTTP " + statusCode
                : "unrecognised response (HTTP " + statusCode + ", no results section)";
    }

    private List<DatasheetSuggestionDTO> parseResults(String body, Set<String> seen) {
        if (body == null) return List.of();

        List<DatasheetSuggestionDTO> list = new ArrayList<>();
        Matcher m = RESULT_PATTERN.matcher(body);
        while (m.find()) {
            String rawHref = m.group(1);
            String rawTitle = m.group(2);

            String target = resolveTarget(rawHref);
            if (target == null || !target.startsWith("http")) continue;
            if (!isPdfUrl(target)) continue;
            if (!seen.add(target)) continue;

            String title = TAG_PATTERN.matcher(rawTitle).replaceAll("").strip();
            String source = hostOf(target);

            list.add(DatasheetSuggestionDTO.builder()
                    .url(target)
                    .title(title.isBlank() ? null : title)
                    .source(source)
                    .build());
        }
        return list;
    }

    /** Verifies candidates concurrently and returns the first {@link #TARGET_RESULTS} live PDFs. */
    private List<DatasheetSuggestionDTO> verifyAll(List<DatasheetSuggestionDTO> candidates) {
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(VERIFY_POOL_SIZE, candidates.size()));
        try {
            List<CompletableFuture<Boolean>> futures = candidates.stream()
                    .map(c -> CompletableFuture.supplyAsync(() -> verifyPdf(c.getUrl()), pool))
                    .toList();

            List<DatasheetSuggestionDTO> verified = new ArrayList<>();
            for (int i = 0; i < candidates.size() && verified.size() < TARGET_RESULTS; i++) {
                try {
                    if (futures.get(i).get(6, TimeUnit.SECONDS)) {
                        verified.add(candidates.get(i));
                    }
                } catch (Exception e) {
                    // timed out / errored — treat as dead, not a usable result
                }
            }
            return verified;
        } finally {
            pool.shutdown();
        }
    }

    /** Confirms a candidate URL is still live and actually serves a PDF (not a redirect to HTML/404). */
    private boolean verifyPdf(String url) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("User-Agent",
                "Mozilla/5.0 (X11; Linux x86_64; rv:124.0) Gecko/20100101 Firefox/124.0");

        try {
            ResponseEntity<Void> head = verifyRestTemplate.exchange(
                    url, HttpMethod.HEAD, new HttpEntity<>(headers), Void.class);
            if (!head.getStatusCode().is2xxSuccessful()) {
                return false; // dead link (404, etc.) — no point falling back further
            }
            String contentType = head.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE);
            if (contentType != null && contentType.toLowerCase().contains("pdf")) {
                return true;
            }
            // HEAD succeeded but content-type is missing/wrong (some servers lie or omit it) —
            // fall through to a byte-level check.
        } catch (Exception e) {
            // HEAD not supported/blocked by the server — fall through to a ranged GET.
        }
        return verifyByMagicBytes(url, headers);
    }

    private boolean verifyByMagicBytes(String url, HttpHeaders headers) {
        try {
            HttpHeaders ranged = new HttpHeaders();
            ranged.putAll(headers);
            ranged.set("Range", "bytes=0-1023");

            ResponseEntity<byte[]> resp = verifyRestTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(ranged), byte[].class);
            if (!resp.getStatusCode().is2xxSuccessful()) return false;

            byte[] bodyBytes = resp.getBody();
            if (bodyBytes == null || bodyBytes.length < 5) return false;
            return new String(bodyBytes, 0, 5, StandardCharsets.US_ASCII).equals("%PDF-");
        } catch (Exception e) {
            return false;
        }
    }

    /** DuckDuckGo's HTML results wrap the real URL as `uddg=` on a `//duckduckgo.com/l/?...` redirect. */
    private String resolveTarget(String href) {
        String decoded = htmlUnescape(href);
        int uddgIdx = decoded.indexOf("uddg=");
        if (uddgIdx < 0) {
            return decoded.startsWith("http") ? decoded : null;
        }
        String tail = decoded.substring(uddgIdx + 5);
        int amp = tail.indexOf('&');
        String encodedTarget = amp >= 0 ? tail.substring(0, amp) : tail;
        try {
            return URLDecoder.decode(encodedTarget, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    /** Result pages are almost always unusable HTML wrappers — only keep links that are actual PDFs. */
    private static boolean isPdfUrl(String url) {
        try {
            String path = URI.create(url).getPath();
            return path != null && path.toLowerCase().endsWith(".pdf");
        } catch (Exception e) {
            return false;
        }
    }

    private static String htmlUnescape(String s) {
        return s.replace("&amp;", "&");
    }

    private static String hostOf(String url) {
        try {
            String host = URI.create(url).getHost();
            return host == null ? null : host.replaceFirst("^www\\.", "");
        } catch (Exception e) {
            return null;
        }
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /**
     * Backed by Apache HttpClient rather than {@code SimpleClientHttpRequestFactory}: the JDK's
     * {@code HttpURLConnection} silently refuses to follow a redirect that changes protocol, so an
     * {@code http://} candidate that redirects to {@code https://} verified as "not a PDF" and was
     * dropped — losing good results rather than returning bad ones, which is why it went unnoticed.
     */
    private static RestTemplate buildVerifyRestTemplate() {
        PoolingHttpClientConnectionManager connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(Timeout.ofSeconds(3))
                        .build())
                .build();
        RequestConfig requestConfig = RequestConfig.custom()
                .setResponseTimeout(Timeout.ofSeconds(4))
                .setRedirectsEnabled(true)
                .setMaxRedirects(10)
                .build();
        CloseableHttpClient httpClient = HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(requestConfig)
                .build();
        return new RestTemplate(new HttpComponentsClientHttpRequestFactory(httpClient));
    }
}
