package com.clele.parts.service;

import com.clele.parts.dto.DatasheetSuggestionDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Datasheet PDF search backed by DuckDuckGo's HTML (no-JS) search page.
 *
 * Flow: GET html.duckduckgo.com/html/?q=… and scrape result links + titles. DuckDuckGo wraps
 * outbound links behind a redirect (`//duckduckgo.com/l/?uddg=<encoded-target>`); the real target
 * is extracted from the `uddg` query parameter.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DuckDuckGoDatasheetService {

    private static final String DDG_HTML = "https://html.duckduckgo.com/html/";

    private static final Pattern RESULT_PATTERN = Pattern.compile(
            "<a[^>]*class=\"result__a\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>",
            Pattern.DOTALL);
    private static final Pattern TAG_PATTERN = Pattern.compile("<[^>]+>");

    private final RestTemplate restTemplate;

    public List<DatasheetSuggestionDTO> search(String query) {
        try {
            String q = query + " datasheet filetype:pdf";
            String url = DDG_HTML + "?q=" + encode(q);

            HttpHeaders headers = new HttpHeaders();
            headers.set("User-Agent",
                    "Mozilla/5.0 (X11; Linux x86_64; rv:124.0) Gecko/20100101 Firefox/124.0");
            headers.set("Accept-Language", "en-US,en;q=0.5");

            ResponseEntity<String> resp = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(headers), String.class);

            if (!resp.getStatusCode().is2xxSuccessful()) {
                log.warn("DuckDuckGo datasheet search returned HTTP {} for '{}'", resp.getStatusCode(), query);
                return List.of();
            }
            List<DatasheetSuggestionDTO> results = parseResults(resp.getBody());
            if (results.isEmpty()) {
                log.warn("DuckDuckGo datasheet search for '{}' returned no usable results", query);
            }
            return results;
        } catch (RestClientException e) {
            log.warn("DuckDuckGo datasheet search for '{}' failed: HTTP/network error: {}", query, e.toString());
            return List.of();
        } catch (Exception e) {
            log.warn("DuckDuckGo datasheet search for '{}' failed", query, e);
            return List.of();
        }
    }

    private List<DatasheetSuggestionDTO> parseResults(String body) {
        if (body == null) return List.of();

        List<DatasheetSuggestionDTO> list = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Matcher m = RESULT_PATTERN.matcher(body);
        while (m.find() && list.size() < 8) {
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
}
