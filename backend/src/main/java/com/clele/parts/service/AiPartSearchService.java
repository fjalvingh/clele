package com.clele.parts.service;

import com.clele.parts.dto.ImageSuggestionDTO;
import com.clele.parts.dto.PartSearchResultDTO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class AiPartSearchService {

    private final DuckDuckGoImageService duckDuckGoImageService;

    private static final String API_URL = "https://api.anthropic.com/v1/messages";
    private static final String API_VERSION = "2023-06-01";
    private static final String WIKIMEDIA_API =
            "https://commons.wikimedia.org/w/api.php";

    private static final String IMAGE_PROMPT = """
            You are helping source photographs of electronic components for an inventory system.
            For the electronic component or package "%s", suggest up to 5 direct image URLs.

            Return ONLY a valid JSON array with no markdown, no explanation:
            [{"url": "https://...", "description": "brief label"}]

            Focus on Wikimedia Commons uploads (https://upload.wikimedia.org/wikipedia/commons/...) \
            or official manufacturer/distributor product image URLs. \
            Return [] if you truly have no suggestions.
            """;

    private static final String SYSTEM_PROMPT = """
            You are an electronic components database assistant. \
            Use web search to look up accurate information about the requested component from \
            Mouser, DigiKey, manufacturer datasheets, or other authoritative sources before responding.

            Return ONLY a valid JSON array with no markdown formatting, no code blocks, no explanation. \
            Each object must have these fields:
            - mpn: manufacturer part number (string, required)
            - manufacturer: manufacturer name (string or null)
            - shortDescription: brief one-line description (string or null)
            - datasheetUrl: datasheet URL found in search results (string or null)
            - category: component category such as "Transistors" or "Logic ICs" (string or null)
            - specs: array of "Name: Value" strings for verified key specifications, \
              e.g. ["Package: DIP-16", "Supply Voltage: 3–18V", "Channels: 4"]

            Be precise: verify the correct package type, pin count, and function from the search results. \
            Only include real components with accurate, search-verified data. \
            If no components match, return an empty array [].
            """;

    @Value("${anthropic.api-key:}")
    private String apiKey;

    @Value("${anthropic.model:claude-haiku-4-5-20251001}")
    private String model;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public List<PartSearchResultDTO> search(String query) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "AI part search not configured. Set anthropic.api-key in application.yml.");
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-api-key", apiKey);
        headers.set("anthropic-version", API_VERSION);
        headers.set("anthropic-beta", "web-search-2025-03-05");

        Map<String, Object> body = Map.of(
                "model", model,
                "max_tokens", 4096,
                "system", SYSTEM_PROMPT,
                "tools", List.of(Map.of("type", "web_search_20250305", "name", "web_search")),
                "messages", List.of(Map.of("role", "user", "content", query))
        );

        ResponseEntity<String> response;
        try {
            response = restTemplate.exchange(API_URL, HttpMethod.POST,
                    new HttpEntity<>(body, headers), String.class);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "AI search request failed: " + e.getMessage());
        }

        try {
            return parseResponse(response.getBody());
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Failed to parse AI response: " + e.getMessage());
        }
    }

    private List<PartSearchResultDTO> parseResponse(String body) throws Exception {
        JsonNode root = objectMapper.readTree(body);

        if (root.has("error")) {
            String msg = root.path("error").path("message").asText("Unknown error");
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Anthropic API error: " + msg);
        }

        // With web search enabled the content array contains tool_use blocks before the
        // final text block — find the last text-type block.
        String text = null;
        for (JsonNode item : root.path("content")) {
            if ("text".equals(item.path("type").asText(""))) {
                text = item.path("text").asText("").strip();
            }
        }
        if (text == null || text.isBlank()) return List.of();

        // Strip markdown code block if the model wraps the JSON anyway
        if (text.startsWith("```")) {
            text = text.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("```\\s*$", "").strip();
        }

        JsonNode array = objectMapper.readTree(text);
        if (!array.isArray()) {
            return List.of();
        }

        List<PartSearchResultDTO> results = new ArrayList<>();
        for (JsonNode part : array) {
            String mpn = part.path("mpn").asText("").strip();
            if (mpn.isBlank()) continue;

            List<String> specs = new ArrayList<>();
            for (JsonNode spec : part.path("specs")) {
                String s = spec.asText("").strip();
                if (!s.isBlank()) specs.add(s);
            }

            JsonNode dsNode = part.path("datasheetUrl");
            String datasheetUrl = (dsNode.isNull() || dsNode.isMissingNode())
                    ? null : dsNode.asText("").strip();
            if (datasheetUrl != null && datasheetUrl.isBlank()) datasheetUrl = null;

            results.add(PartSearchResultDTO.builder()
                    .mpn(mpn)
                    .manufacturer(nullIfBlank(part.path("manufacturer").asText(null)))
                    .shortDescription(nullIfBlank(part.path("shortDescription").asText(null)))
                    .datasheetUrl(datasheetUrl)
                    .category(nullIfBlank(part.path("category").asText(null)))
                    .specs(specs)
                    .build());
        }
        return results;
    }

    public List<ImageSuggestionDTO> searchImages(String query) {
        // 1. Try DuckDuckGo — best relevance, no API key needed
        List<ImageSuggestionDTO> ddg = duckDuckGoImageService.search(query);
        if (!ddg.isEmpty()) {
            return ddg;
        }

        // 2. Fall back to Wikimedia Commons
        List<ImageSuggestionDTO> wikimedia = searchWikimediaImages(query);
        if (!wikimedia.isEmpty()) {
            return wikimedia;
        }

        // Fall back to AI suggestions
        if (apiKey == null || apiKey.isBlank()) {
            return List.of();
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-api-key", apiKey);
        headers.set("anthropic-version", API_VERSION);

        String prompt = String.format(IMAGE_PROMPT, query);
        Map<String, Object> body = Map.of(
                "model", model,
                "max_tokens", 1024,
                "messages", List.of(Map.of("role", "user", "content", prompt))
        );

        try {
            ResponseEntity<String> response = restTemplate.exchange(API_URL, HttpMethod.POST,
                    new HttpEntity<>(body, headers), String.class);
            return parseImageResponse(response.getBody());
        } catch (Exception e) {
            return List.of();
        }
    }

    // ── Wikimedia Commons image search ──────────────────────────────────────────

    private List<ImageSuggestionDTO> searchWikimediaImages(String query) {
        try {
            String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
            // generator=search + prop=imageinfo + iiurlwidth causes the API to pre-render
            // a thumbnail and return its URL in "thumburl" — guaranteed to be valid.
            String url = WIKIMEDIA_API
                    + "?action=query&generator=search&gsrsearch=" + encoded
                    + "&gsrnamespace=6&format=json&gsrlimit=12"
                    + "&prop=imageinfo&iiprop=url&iiurlwidth=400";

            // Wikimedia requires a descriptive User-Agent; plain Java UA is rejected.
            HttpHeaders headers = new HttpHeaders();
            headers.set("User-Agent", "Clele/1.0 (electronic parts inventory; contact@clele.local)");

            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(headers), String.class);
            return parseWikimediaResponse(response.getBody());
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<ImageSuggestionDTO> parseWikimediaResponse(String body) throws Exception {
        JsonNode root = objectMapper.readTree(body);
        JsonNode pages = root.path("query").path("pages");
        if (!pages.isObject()) return List.of();

        List<ImageSuggestionDTO> results = new ArrayList<>();
        for (JsonNode page : pages) {
            String title = page.path("title").asText("");
            if (!title.startsWith("File:")) continue;

            String filename = title.substring(5);

            // Skip SVGs — their thumburl dimensions vary and are hard to proxy reliably
            String lower = filename.toLowerCase();
            if (lower.endsWith(".svg")) continue;

            JsonNode imageinfo = page.path("imageinfo");
            if (!imageinfo.isArray() || imageinfo.isEmpty()) continue;

            // thumburl is the pre-built thumbnail URL — use it in preference to url
            JsonNode info = imageinfo.get(0);
            String thumbUrl = info.path("thumburl").asText("").strip();
            String directUrl = info.path("url").asText("").strip();
            String imageUrl = thumbUrl.isBlank() ? directUrl : thumbUrl;
            if (imageUrl.isBlank()) continue;

            int dot = filename.lastIndexOf('.');
            String description = dot > 0 ? filename.substring(0, dot) : filename;

            results.add(ImageSuggestionDTO.builder()
                    .url(imageUrl)
                    .description(description)
                    .build());

            if (results.size() >= 5) break;
        }
        return results;
    }

    private List<ImageSuggestionDTO> parseImageResponse(String body) throws Exception {
        JsonNode root = objectMapper.readTree(body);
        if (root.has("error")) return List.of();

        String text = root.path("content").get(0).path("text").asText("").strip();
        if (text.startsWith("```")) {
            text = text.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("```\\s*$", "").strip();
        }

        JsonNode array = objectMapper.readTree(text);
        if (!array.isArray()) return List.of();

        List<ImageSuggestionDTO> results = new ArrayList<>();
        for (JsonNode node : array) {
            String url = node.path("url").asText("").strip();
            if (url.isBlank()) continue;
            results.add(ImageSuggestionDTO.builder()
                    .url(url)
                    .description(nullIfBlank(node.path("description").asText(null)))
                    .build());
        }
        return results;
    }

    private static String nullIfBlank(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
