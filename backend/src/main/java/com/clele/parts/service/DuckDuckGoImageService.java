package com.clele.parts.service;

import com.clele.parts.dto.ImageSuggestionDTO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Image search backed by DuckDuckGo's (unofficial) image search API.
 *
 * Flow:
 *  1. GET duckduckgo.com/?q=… to obtain the per-query VQD security token.
 *  2. GET duckduckgo.com/i.js?q=…&vqd=…  to retrieve image results as JSON.
 */
@Service
@RequiredArgsConstructor
public class DuckDuckGoImageService {

    private static final String DDG_SEARCH  = "https://duckduckgo.com/";
    private static final String DDG_IMAGES  = "https://duckduckgo.com/i.js";
    private static final Pattern VQD_PATTERN =
            Pattern.compile("vqd=['\"](\\d+(?:-[a-zA-Z0-9%_+-]+)+)['\"]");

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public List<ImageSuggestionDTO> search(String query) {
        try {
            String vqd = fetchVqd(query);
            if (vqd == null || vqd.isBlank()) return List.of();
            return fetchImages(query, vqd);
        } catch (Exception e) {
            return List.of();
        }
    }

    // ── Step 1: obtain VQD token ─────────────────────────────────────────────

    private String fetchVqd(String query) throws Exception {
        String url = DDG_SEARCH + "?q=" + encode(query) + "&iax=images&ia=images";
        ResponseEntity<String> resp = restTemplate.exchange(
                url, HttpMethod.GET, new HttpEntity<>(browserHeaders(null)), String.class);

        String body = resp.getBody();
        if (body == null) return null;

        Matcher m = VQD_PATTERN.matcher(body);
        return m.find() ? m.group(1) : null;
    }

    // ── Step 2: fetch image results ──────────────────────────────────────────

    private List<ImageSuggestionDTO> fetchImages(String query, String vqd) throws Exception {
        String url = DDG_IMAGES
                + "?q=" + encode(query)
                + "&o=json&vqd=" + encode(vqd)
                + "&f=,,,,,&s=0&l=us-en";

        HttpHeaders headers = browserHeaders("https://duckduckgo.com/");
        headers.setAccept(List.of(MediaType.APPLICATION_JSON, MediaType.ALL));

        ResponseEntity<String> resp = restTemplate.exchange(
                url, HttpMethod.GET, new HttpEntity<>(headers), String.class);

        return parseResults(resp.getBody());
    }

    private List<ImageSuggestionDTO> parseResults(String body) throws Exception {
        if (body == null) return List.of();
        JsonNode root = objectMapper.readTree(body);
        JsonNode results = root.path("results");
        if (!results.isArray()) return List.of();

        List<ImageSuggestionDTO> list = new ArrayList<>();
        for (JsonNode r : results) {
            String imageUrl     = r.path("image").asText("").strip();
            String thumbnailUrl = r.path("thumbnail").asText("").strip();
            String title        = r.path("title").asText("").strip();

            if (imageUrl.isBlank()) continue;
            // Skip obviously bad URLs
            if (!imageUrl.startsWith("http")) continue;

            list.add(ImageSuggestionDTO.builder()
                    .url(imageUrl)
                    .thumbnailUrl(thumbnailUrl.isBlank() ? null : thumbnailUrl)
                    .description(title.isBlank() ? null : title)
                    .build());

            if (list.size() >= 6) break;
        }
        return list;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static HttpHeaders browserHeaders(String referer) {
        HttpHeaders h = new HttpHeaders();
        h.set("User-Agent",
                "Mozilla/5.0 (X11; Linux x86_64; rv:124.0) Gecko/20100101 Firefox/124.0");
        h.set("Accept-Language", "en-US,en;q=0.5");
        if (referer != null) h.set("Referer", referer);
        return h;
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
