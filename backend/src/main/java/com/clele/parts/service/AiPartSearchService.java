package com.clele.parts.service;

import com.clele.parts.dto.DatasheetSearchResponseDTO;
import com.clele.parts.dto.DatasheetSuggestionDTO;
import com.clele.parts.dto.ImageSuggestionDTO;
import com.clele.parts.dto.PartSearchResultDTO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
public class AiPartSearchService {

    private final DuckDuckGoImageService duckDuckGoImageService;
    private final DuckDuckGoDatasheetService duckDuckGoDatasheetService;
    private final SpecFieldCatalog specFieldCatalog;
    private final AiCredentialsService aiCredentials;

    private static final String API_URL = "https://api.anthropic.com/v1/messages";
    private static final String API_VERSION = "2023-06-01";
    private static final String WIKIMEDIA_API =
            "https://commons.wikimedia.org/w/api.php";

    /** What the web fetch tool accepts; anything longer comes back as {@code url_too_long}. */
    private static final int MAX_FETCH_URL_LENGTH = 250;
    /** One page is the point of the URL lookup — the spare fetch is for a redirect. */
    private static final int MAX_FETCHES = 2;
    /** Caps the page text that enters the prompt (~250 kB of HTML). PDFs are not capped by it. */
    private static final int MAX_FETCH_CONTENT_TOKENS = 60_000;

    private static final String IMAGE_PROMPT = """
            You are helping source photographs of electronic components for an inventory system.
            For the electronic component or package "%s", suggest up to 5 direct image URLs.

            Return ONLY a valid JSON array with no markdown, no explanation:
            [{"url": "https://...", "description": "brief label"}]

            Focus on Wikimedia Commons uploads (https://upload.wikimedia.org/wikipedia/commons/...) \
            or official manufacturer/distributor product image URLs. \
            Return [] if you truly have no suggestions.
            """;

    private static final String DATASHEET_PROMPT = """
            You are helping locate manufacturer datasheets for an electronic component inventory system.
            For the electronic component "%s", suggest up to 5 direct URLs to its datasheet (PDF).

            Return ONLY a valid JSON array with no markdown, no explanation:
            [{"url": "https://...", "title": "brief label", "source": "hostname"}]

            Prefer official manufacturer or authorized distributor (Mouser, DigiKey, ...) hosted PDFs. \
            Return [] if you truly have no suggestions.
            """;

    /**
     * How the model is told to find the component. The two openings differ in their source — a web
     * search of its own, or the one page the user pasted — and are the only part that may: what a
     * result must look like is {@link #RESULT_CONTRACT}, shared, because a spec key described two
     * ways lands in {@code part.specs} as two different fields. Same reason
     * {@link SpecFieldCatalog} exists.
     */
    private static final String SEARCH_INTRO = """
            You are an electronic components database assistant. \
            Use web search to look up accurate information about the requested component from \
            Mouser, DigiKey, manufacturer datasheets, or other authoritative sources before responding.
            """;

    private static final String URL_INTRO = """
            You are an electronic components database assistant. \
            The user gives you the address of ONE web page: a distributor product page, a \
            manufacturer product page, or a datasheet PDF. \
            Use the web_fetch tool to read exactly that address, and describe the component that \
            page is about.
            """;

    private static final String RESULT_CONTRACT = """
            Return ONLY a valid JSON array with no markdown formatting, no code blocks, no explanation. \
            Each object must have these fields:
            - mpn: manufacturer part number (string, required)
            - manufacturer: manufacturer name (string or null)
            - shortDescription: brief one-line description (string or null)
            - datasheetUrl: URL of the datasheet PDF, if the source gives one (string or null)
            - category: component category such as "Transistors" or "Logic ICs" (string or null)
            - specs: array of "Name: Value" strings for verified key specifications

            IMPORTANT: For spec names you MUST use EXACTLY these predefined keys when applicable. \
            Each entry below is the exact key to use, followed by a human-readable title in parentheses \
            (a hint only — do NOT use the title as the key). \
            Use only the numeric value without repeating the unit that is already in the key:
            %s

            Only include specs where you have a verified value. \
            For SELECT-type specs, use one of the allowed option values listed in parentheses. \
            For NUMBER specs with multiple unit options shown after the name, append the unit to the value \
            (e.g. "Capacitance: 100 nF", "Resistance: 4.7 kΩ"). \
            For NUMBER specs with a single unit, just provide the numeric value.
            """;

    private static final String SEARCH_OUTRO = """
            Be precise: verify the correct package type, pin count, and function from the search results. \
            Only include real components with accurate, search-verified data. \
            If no components match, return an empty array [].
            """;

    private static final String URL_OUTRO = """
            Take every value from that page. Do not fill gaps from memory and do not fetch any \
            other address: leave a field null rather than guessing it. \
            If the page offers several orderable variants of one component, return one entry per \
            distinct manufacturer part number, the most prominent one first. \
            If the page could not be read, or is not about an electronic component, \
            return an empty array [].
            """;

    @Value("${anthropic.pricing.input-per-mtok:1.00}")
    private double inputPerMTok;

    @Value("${anthropic.pricing.output-per-mtok:5.00}")
    private double outputPerMTok;

    @Value("${anthropic.pricing.cache-read-multiplier:0.1}")
    private double cacheReadMultiplier;

    @Value("${anthropic.pricing.cache-write-multiplier:1.25}")
    private double cacheWriteMultiplier;

    @Value("${anthropic.pricing.web-search-per-ksearch:10.00}")
    private double webSearchPerKSearch;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public List<PartSearchResultDTO> search(String query) {
        // Whose key, and which model: the organisation's own, since it pays for this call. Throws
        // 503 naming the reason when there is no usable key.
        AiCredentialsService.Credentials credentials = aiCredentials.require();

        HttpHeaders headers = headers(credentials);
        headers.set("anthropic-beta", "web-search-2025-03-05");

        SystemPrompt prompt = buildSystemPrompt(SEARCH_INTRO, SEARCH_OUTRO);
        Map<String, Object> body = Map.of(
                "model", credentials.model(),
                "max_tokens", 4096,
                "system", prompt.text(),
                "tools", List.of(Map.of("type", "web_search_20250305", "name", "web_search")),
                "messages", List.of(Map.of("role", "user", "content", query))
        );

        ResponseEntity<String> response;
        long startedAt = System.currentTimeMillis();
        try {
            response = restTemplate.exchange(API_URL, HttpMethod.POST,
                    new HttpEntity<>(body, headers), String.class);
        } catch (Exception e) {
            throw aiCredentials.translate(e, "AI search request failed");
        }

        try {
            List<PartSearchResultDTO> results = parseResponse(response.getBody());
            aiCredentials.noteSuccess();
            logUsage("web-search", response.getBody(), query, prompt,
                    results.size(), System.currentTimeMillis() - startedAt);
            return results;
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Failed to parse AI response: " + e.getMessage());
        }
    }

    /**
     * Read one page the user pasted and describe the component on it.
     *
     * <p>The escape hatch behind Quick Add's "New search": when the web search misses a part —
     * a house-branded module, a shop the search engine does not index, a PDF nobody links — the
     * user can still point at the page that does describe it. Same output as {@link #search}, so
     * the caller shows the same result cards and the same confirm step; only the source differs.
     *
     * <p>The model reads the page through Anthropic's server-side {@code web_fetch} tool rather
     * than us downloading it: it renders HTML and PDF alike, and it is the only way the fetched
     * bytes reach the model without a round trip through this process. That tool may only fetch a
     * URL that already appears in the conversation, which is exactly the one the user pasted —
     * a model that invents a second address is refused by the API, not by us.
     *
     * <p>Web fetch itself is free; the cost is the fetched page as input tokens, which is what
     * {@code max_content_tokens} bounds — a datasheet PDF is easily 125k tokens left uncapped.
     */
    public List<PartSearchResultDTO> searchByUrl(String url) {
        String target = url == null ? "" : url.strip();
        if (!target.regionMatches(true, 0, "http://", 0, 7)
                && !target.regionMatches(true, 0, "https://", 0, 8)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Paste the full address of the page, starting with http:// or https://");
        }
        // The tool rejects anything longer with url_too_long, and says so only after we have paid
        // for the request.
        if (target.length() > MAX_FETCH_URL_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "That address is too long to fetch (over " + MAX_FETCH_URL_LENGTH + " characters).");
        }
        AiCredentialsService.Credentials credentials = aiCredentials.require();

        HttpHeaders headers = headers(credentials);

        SystemPrompt prompt = buildSystemPrompt(URL_INTRO, URL_OUTRO);
        Map<String, Object> body = Map.of(
                "model", credentials.model(),
                "max_tokens", 4096,
                "system", prompt.text(),
                "tools", List.of(Map.of(
                        "type", "web_fetch_20250910",
                        "name", "web_fetch",
                        "max_uses", MAX_FETCHES,
                        "max_content_tokens", MAX_FETCH_CONTENT_TOKENS)),
                "messages", List.of(Map.of("role", "user",
                        "content", "Read " + target + " and return the component it describes."))
        );

        ResponseEntity<String> response;
        long startedAt = System.currentTimeMillis();
        try {
            response = restTemplate.exchange(API_URL, HttpMethod.POST,
                    new HttpEntity<>(body, headers), String.class);
        } catch (Exception e) {
            throw aiCredentials.translate(e, "AI lookup request failed");
        }

        try {
            requireFetchSucceeded(response.getBody());
            List<PartSearchResultDTO> results = parseResponse(response.getBody());
            aiCredentials.noteSuccess();
            logUsage("url", response.getBody(), target, prompt,
                    results.size(), System.currentTimeMillis() - startedAt);
            return results;
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Failed to parse AI response: " + e.getMessage());
        }
    }

    /**
     * Fail loudly when the page could not be read.
     *
     * <p>A failed fetch is not an API error: the call returns 200 with an error block, the model
     * carries on, and — having been told to return {@code []} when it cannot read the page — it
     * usually does. Without this the user would see "no results", which reads as "that page has
     * nothing on it" rather than "the site refused us", and they would try the same URL again.
     */
    private void requireFetchSucceeded(String body) throws Exception {
        for (JsonNode item : objectMapper.readTree(body).path("content")) {
            if (!"web_fetch_tool_result".equals(item.path("type").asText(""))) continue;
            JsonNode content = item.path("content");
            if (!"web_fetch_tool_result_error".equals(content.path("type").asText(""))) continue;
            String code = content.path("error_code").asText("");
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Could not read that page: " + describeFetchError(code));
        }
    }

    private static String describeFetchError(String code) {
        return switch (code) {
            case "url_not_accessible" -> "the site did not return it (it may be down, or it blocks automated readers).";
            case "url_not_allowed" -> "that address may not be fetched — it is private, or its robots.txt forbids it.";
            case "unsupported_content_type" -> "only web pages and PDF files can be read.";
            case "too_many_requests" -> "too many requests right now — try again in a minute.";
            case "url_too_long", "invalid_tool_input" -> "that address is not one we can fetch.";
            case "url_not_in_prior_context" -> "the lookup tried to read a different address than the one given.";
            default -> "the fetch failed (" + (code.isBlank() ? "unknown error" : code) + ").";
        };
    }

    /**
     * One INFO line per lookup with what it cost and why. Until this existed nothing recorded the
     * price of a part search, so the figures in SPECS.md had to be produced by replaying the call by
     * hand — which is no way to tell whether a change made things better or worse.
     *
     * <p>Note {@code input_tokens} is only the <em>uncached</em> remainder: the prompt actually sent
     * is that plus the cache-creation and cache-read counts, which is why {@code promptTok} is a sum
     * rather than the field. Today nothing sets {@code cache_control}, so the cache figures are 0 and
     * the sum equals {@code input_tokens} — they are logged now so that turning caching on shows up
     * here as a drop rather than as a mystery.
     *
     * <p>The cost is an estimate from configured rates, not a billed amount; it names the model it
     * priced so a rate left behind by a model change is visible rather than silently wrong.
     *
     * <p>Logging must never break a search that otherwise worked, so every failure in here is
     * swallowed at DEBUG.
     */
    private void logUsage(String source, String body, String query,
                          SystemPrompt prompt, int resultCount, long millis) {
        try {
            JsonNode root = objectMapper.readTree(body);
            // The model comes off the response rather than the request: it is what actually served
            // the call, which is the figure the cost estimate below should be read against.
            String model = root.path("model").asText("unknown");
            JsonNode usage = root.path("usage");
            long input = usage.path("input_tokens").asLong(0);
            long output = usage.path("output_tokens").asLong(0);
            long cacheWrite = usage.path("cache_creation_input_tokens").asLong(0);
            long cacheRead = usage.path("cache_read_input_tokens").asLong(0);
            long searches = webSearchCount(body, usage);

            double cost = (input * inputPerMTok
                    + cacheWrite * inputPerMTok * cacheWriteMultiplier
                    + cacheRead * inputPerMTok * cacheReadMultiplier
                    + output * outputPerMTok) / 1_000_000d
                    + searches * webSearchPerKSearch / 1_000d;

            log.info("ai-part-search source={} model={} query=\"{}\" specDefs={} promptChars={} "
                            + "promptTok={} (in={} cacheWrite={} cacheRead={}) outTok={} "
                            + "webSearches={} results={} ms={} estCostUsd={}",
                    source, model, query, prompt.definitionCount(), prompt.text().length(),
                    input + cacheWrite + cacheRead, input, cacheWrite, cacheRead, output,
                    searches, resultCount, millis, String.format("%.4f", cost));
        } catch (Exception e) {
            log.debug("Could not log AI search usage: {}", e.toString());
        }
    }

    /**
     * How many billable web searches the model ran. Anthropic reports this under
     * {@code usage.server_tool_use}; when that is absent, count the {@code server_tool_use} blocks in
     * the content array instead — the model emits one per search, so the two agree.
     */
    private long webSearchCount(String body, JsonNode usage) throws Exception {
        JsonNode reported = usage.path("server_tool_use").path("web_search_requests");
        if (reported.isNumber()) return reported.asLong();
        long counted = 0;
        for (JsonNode item : objectMapper.readTree(body).path("content")) {
            // Name-checked, not just type-checked: a URL lookup's server_tool_use blocks are
            // web_fetch calls, which are billed as tokens only. Counting those as searches would
            // put a phantom cent on every line.
            if ("server_tool_use".equals(item.path("type").asText(""))
                    && "web_search".equals(item.path("name").asText(""))) counted++;
        }
        return counted;
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

        // Extract JSON array from response — the model may wrap it in markdown fences
        // and/or prepend explanatory text before the code block.
        int fenceStart = text.indexOf("```");
        if (fenceStart >= 0) {
            text = text.substring(fenceStart);
            text = text.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("```\\s*$", "").strip();
        } else {
            // No fence — try to find a bare JSON array
            int bracketStart = text.indexOf('[');
            if (bracketStart < 0) {
                // AI returned prose (no results / unknown part) — treat as empty
                return List.of();
            }
            if (bracketStart > 0) {
                text = text.substring(bracketStart);
            }
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

        // Fall back to AI suggestions — only when this organisation has a working key. An
        // unconfigured or exhausted one is not an error here: images are a nice-to-have and the
        // caller shows a search box instead.
        if (!aiCredentials.isUsable()) {
            return List.of();
        }
        AiCredentialsService.Credentials credentials = aiCredentials.require();

        HttpHeaders headers = headers(credentials);

        String prompt = String.format(IMAGE_PROMPT, query);
        Map<String, Object> body = Map.of(
                "model", credentials.model(),
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

    public DatasheetSearchResponseDTO searchDatasheets(String query) {
        return searchDatasheets(query, false);
    }

    /**
     * Web search first, AI as the fallback — and the web search's own outcome travels back with the
     * results. "Blocked by a bot challenge" and "searched and found nothing" produce the same empty
     * list, and only the first is a reason to try again in a minute.
     */
    public DatasheetSearchResponseDTO searchDatasheets(String query, boolean forceAi) {
        String webStatus = "SKIPPED";
        String webDetail = null;

        // 1. Try DuckDuckGo — best relevance, no API key needed (unless the caller asked to skip it)
        if (!forceAi) {
            DuckDuckGoDatasheetService.SearchResult ddg = duckDuckGoDatasheetService.search(query);
            webStatus = ddg.status().name();
            webDetail = ddg.detail();
            if (!ddg.results().isEmpty()) {
                return DatasheetSearchResponseDTO.builder()
                        .results(ddg.results())
                        .source("WEB")
                        .webSearchStatus(webStatus)
                        .build();
            }
        }

        // Fall back to AI suggestions, if this organisation has a working key. Without one the web
        // search's own outcome still travels back, which is the honest answer: the datasheet was
        // not found, and no AI was asked.
        if (!aiCredentials.isUsable()) {
            return noResults(webStatus, webDetail);
        }
        AiCredentialsService.Credentials credentials = aiCredentials.require();

        HttpHeaders headers = headers(credentials);

        String prompt = String.format(DATASHEET_PROMPT, query);
        Map<String, Object> body = Map.of(
                "model", credentials.model(),
                "max_tokens", 1024,
                "messages", List.of(Map.of("role", "user", "content", prompt))
        );

        try {
            ResponseEntity<String> response = restTemplate.exchange(API_URL, HttpMethod.POST,
                    new HttpEntity<>(body, headers), String.class);
            List<DatasheetSuggestionDTO> suggestions = parseDatasheetResponse(response.getBody());
            if (suggestions.isEmpty()) {
                return noResults(webStatus, webDetail);
            }
            return DatasheetSearchResponseDTO.builder()
                    .results(suggestions)
                    .source("AI")
                    .webSearchStatus(webStatus)
                    .detail(webDetail)
                    .build();
        } catch (Exception e) {
            return noResults(webStatus, webDetail);
        }
    }

    /**
     * Check the organisation's key against the API, cheaply and on purpose.
     *
     * <p>One token of output on a one-word prompt — a fraction of a cent — which is the only way to
     * tell a good key from a revoked one or an empty balance without running a real lookup. Used by
     * the admin screen's "Test connection", and it is also the way back from a recorded failure:
     * it resolves credentials {@link AiCredentialsService#requireForProbe() ignoring} that failure,
     * and a success clears it.
     */
    public void probe() {
        AiCredentialsService.Credentials credentials = aiCredentials.requireForProbe();
        Map<String, Object> body = Map.of(
                "model", credentials.model(),
                "max_tokens", 1,
                "messages", List.of(Map.of("role", "user", "content", "ping"))
        );
        try {
            restTemplate.exchange(API_URL, HttpMethod.POST,
                    new HttpEntity<>(body, headers(credentials)), String.class);
        } catch (Exception e) {
            throw aiCredentials.translate(e, "Anthropic did not accept the request");
        }
        aiCredentials.noteSuccess();
    }

    /** The three headers every call needs. The key is the organisation's, never the installation's. */
    private static HttpHeaders headers(AiCredentialsService.Credentials credentials) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-api-key", credentials.apiKey());
        headers.set("anthropic-version", API_VERSION);
        return headers;
    }

    private static DatasheetSearchResponseDTO noResults(String webStatus, String webDetail) {
        return DatasheetSearchResponseDTO.builder()
                .results(List.of())
                .source("NONE")
                .webSearchStatus(webStatus)
                .detail(webDetail)
                .build();
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

    private List<DatasheetSuggestionDTO> parseDatasheetResponse(String body) throws Exception {
        JsonNode root = objectMapper.readTree(body);
        if (root.has("error")) return List.of();

        String text = root.path("content").get(0).path("text").asText("").strip();
        if (text.startsWith("```")) {
            text = text.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("```\\s*$", "").strip();
        }

        JsonNode array = objectMapper.readTree(text);
        if (!array.isArray()) return List.of();

        List<DatasheetSuggestionDTO> results = new ArrayList<>();
        for (JsonNode node : array) {
            String url = node.path("url").asText("").strip();
            if (url.isBlank() || !url.toLowerCase().contains(".pdf")) continue;
            results.add(DatasheetSuggestionDTO.builder()
                    .url(url)
                    .title(nullIfBlank(node.path("title").asText(null)))
                    .source(nullIfBlank(node.path("source").asText(null)))
                    .build());
        }
        return results;
    }

    /**
     * The system prompt plus the figure that explains its size. Almost the whole cost of a lookup is
     * this prompt, and its length is driven by how many spec definitions the organisation has — so
     * the two travel together to the log line, where a cost can be read against the thing that
     * caused it.
     */
    private record SystemPrompt(String text, int definitionCount) {}

    private SystemPrompt buildSystemPrompt(String intro, String outro) {
        // The prompt describes the current organisation's spec fields, so the AI returns keys that
        // match this tenant's part.specs schema. Rendered by the shared catalogue so the datasheet
        // reader describes the same fields the same way.
        SpecFieldCatalog.Fields fields = specFieldCatalog.render();
        String template = intro + "\n" + RESULT_CONTRACT + "\n" + outro;
        return new SystemPrompt(String.format(template, fields.text()), fields.count());
    }

    private static String nullIfBlank(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
