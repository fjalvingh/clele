package com.clele.parts.service;

import com.clele.parts.dto.OctopartResultDTO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thin client for the Nexar Supply API (the current OctoPart API): OAuth2 client-credentials to
 * obtain a bearer token, then a GraphQL {@code supSearchMpn} query. Credentials are supplied
 * per-call because each user runs on their own contract. Tokens are cached in-memory per client id
 * (the token fetch itself is free, but reusing it avoids needless round-trips).
 */
@Service
@RequiredArgsConstructor
public class NexarApiService {

    private static final String TOKEN_URL = "https://identity.nexar.com/connect/token";
    private static final String GRAPHQL_URL = "https://api.nexar.com/graphql";
    private static final String SCOPE = "supply.domain";

    private static final String SEARCH_QUERY = """
            query ($q: String!, $limit: Int!) {
              supSearchMpn(q: $q, limit: $limit) {
                results {
                  part {
                    id
                    mpn
                    manufacturer { name }
                    shortDescription
                    bestDatasheet { url }
                    specs {
                      attribute { name shortname }
                      displayValue
                    }
                  }
                }
              }
            }
            """;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    /** Cached tokens keyed by client id, with a small safety margin before expiry. */
    private final Map<String, CachedToken> tokenCache = new ConcurrentHashMap<>();

    private record CachedToken(String token, Instant expiresAt) {
        boolean valid() {
            return token != null && Instant.now().isBefore(expiresAt);
        }
    }

    /**
     * Obtain a bearer token for the given credentials. Fetching the token is free (it does not spend
     * a Supply API request), so callers can do this before committing the user's quota.
     */
    public String authenticate(String clientId, String clientSecret) {
        return getAccessToken(clientId, clientSecret);
    }

    /** Run the (billable) MPN search GraphQL query with a previously obtained token. */
    public List<OctopartResultDTO> search(String token, String query) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);

        Map<String, Object> body = Map.of(
                "query", SEARCH_QUERY,
                "variables", Map.of("q", query, "limit", 10));

        ResponseEntity<String> response;
        try {
            response = restTemplate.exchange(GRAPHQL_URL, HttpMethod.POST,
                    new HttpEntity<>(body, headers), String.class);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "OctoPart search request failed: " + e.getMessage());
        }

        try {
            return parseResults(response.getBody());
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Failed to parse OctoPart response: " + e.getMessage());
        }
    }

    private List<OctopartResultDTO> parseResults(String body) throws Exception {
        JsonNode root = objectMapper.readTree(body);

        if (root.has("errors") && root.path("errors").isArray() && !root.path("errors").isEmpty()) {
            String msg = root.path("errors").get(0).path("message").asText("Unknown error");
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "OctoPart API error: " + msg);
        }

        JsonNode results = root.path("data").path("supSearchMpn").path("results");
        if (!results.isArray()) return List.of();

        List<OctopartResultDTO> out = new ArrayList<>();
        for (JsonNode node : results) {
            JsonNode part = node.path("part");
            String id = part.path("id").asText("").strip();
            if (id.isBlank()) continue;

            Map<String, Object> specs = new LinkedHashMap<>();
            String footprint = null;
            for (JsonNode spec : part.path("specs")) {
                String shortname = spec.path("attribute").path("shortname").asText("").strip();
                String name = spec.path("attribute").path("name").asText("").strip();
                String value = spec.path("displayValue").asText("").strip();
                String key = !shortname.isBlank() ? shortname : name;
                if (key.isBlank() || value.isBlank()) continue;
                specs.put(key, value);
                if (footprint == null && isPackageAttribute(shortname, name)) {
                    footprint = value;
                }
            }

            out.add(OctopartResultDTO.builder()
                    .octopartId(id)
                    .mpn(nullIfBlank(part.path("mpn").asText(null)))
                    .manufacturer(nullIfBlank(part.path("manufacturer").path("name").asText(null)))
                    .description(nullIfBlank(part.path("shortDescription").asText(null)))
                    .datasheetUrl(nullIfBlank(part.path("bestDatasheet").path("url").asText(null)))
                    .footprint(footprint)
                    .specs(specs)
                    .build());
        }
        return out;
    }

    /** Heuristic: which OctoPart spec attribute maps to the part's footprint/package. */
    private boolean isPackageAttribute(String shortname, String name) {
        String s = (shortname + " " + name).toLowerCase();
        return s.contains("package") || s.contains("case");
    }

    private String getAccessToken(String clientId, String clientSecret) {
        if (clientId == null || clientId.isBlank() || clientSecret == null || clientSecret.isBlank()) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED,
                    "OctoPart credentials are not configured for your account.");
        }
        CachedToken cached = tokenCache.get(clientId);
        if (cached != null && cached.valid()) {
            return cached.token();
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        form.add("scope", SCOPE);

        JsonNode json;
        try {
            ResponseEntity<String> response = restTemplate.exchange(TOKEN_URL, HttpMethod.POST,
                    new HttpEntity<>(form, headers), String.class);
            json = objectMapper.readTree(response.getBody());
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "OctoPart authentication failed (check your credentials): " + e.getMessage());
        }

        String token = json.path("access_token").asText(null);
        if (token == null || token.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "OctoPart authentication returned no access token.");
        }
        long expiresIn = json.path("expires_in").asLong(3600);
        // Refresh a minute early to avoid using a token that expires mid-request.
        Instant expiresAt = Instant.now().plusSeconds(Math.max(60, expiresIn - 60));
        tokenCache.put(clientId, new CachedToken(token, expiresAt));
        return token;
    }

    private static String nullIfBlank(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
