package com.clele.parts.service;

import com.clele.parts.dto.DatasheetSuggestionDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
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

    private final RestTemplate restTemplate;

    /** Short-timeout template dedicated to per-candidate liveness checks (many run in parallel). */
    private final RestTemplate verifyRestTemplate = buildVerifyRestTemplate();

    public List<DatasheetSuggestionDTO> search(String query) {
        String q = query + " datasheet filetype:pdf";

        List<DatasheetSuggestionDTO> candidates = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (int page = 0; page < MAX_PAGES && candidates.size() < MAX_CANDIDATES; page++) {
            List<DatasheetSuggestionDTO> pageResults;
            try {
                pageResults = fetchPage(q, page * PAGE_STEP, seen);
            } catch (RestClientException e) {
                log.warn("DuckDuckGo datasheet search for '{}' failed on page {}: {}", query, page, e.toString());
                break;
            }
            if (pageResults.isEmpty()) {
                break; // no more result pages (or DDG is blocking us) — stop paging
            }
            candidates.addAll(pageResults);
        }

        if (candidates.isEmpty()) {
            log.warn("DuckDuckGo datasheet search for '{}' returned no candidate PDF links", query);
            return List.of();
        }

        List<DatasheetSuggestionDTO> verified = verifyAll(candidates);
        if (verified.isEmpty()) {
            log.warn("DuckDuckGo datasheet search for '{}' found {} pdf-suffixed candidates but none verified live",
                    query, candidates.size());
        }
        return verified;
    }

    private List<DatasheetSuggestionDTO> fetchPage(String q, int offset, Set<String> seen) {
        String url = DDG_HTML + "?q=" + encode(q) + (offset > 0 ? "&s=" + offset : "");

        HttpHeaders headers = new HttpHeaders();
        headers.set("User-Agent",
                "Mozilla/5.0 (X11; Linux x86_64; rv:124.0) Gecko/20100101 Firefox/124.0");
        headers.set("Accept-Language", "en-US,en;q=0.5");

        ResponseEntity<String> resp = restTemplate.exchange(
                url, HttpMethod.GET, new HttpEntity<>(headers), String.class);

        if (!resp.getStatusCode().is2xxSuccessful()) {
            return List.of();
        }
        return parseResults(resp.getBody(), seen);
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

    private static RestTemplate buildVerifyRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3_000);
        factory.setReadTimeout(4_000);
        return new RestTemplate(factory);
    }
}
