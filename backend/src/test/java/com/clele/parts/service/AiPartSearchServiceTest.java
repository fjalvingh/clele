package com.clele.parts.service;

import com.clele.parts.dto.PartSearchResultDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The URL lookup — Quick Add's "read this page instead" path.
 *
 * <p>What is worth pinning is the failure reporting. A page the fetch tool could not read comes
 * back as HTTP <b>200</b> with an error block inside it, and the model, told to return {@code []}
 * when it cannot read the page, obligingly does — so the honest answer "that site refused us" and
 * the misleading one "that page has nothing on it" are the same response unless something looks.
 */
class AiPartSearchServiceTest {

    private final RestTemplate restTemplate = mock(RestTemplate.class);
    private final SpecFieldCatalog specFieldCatalog = mock(SpecFieldCatalog.class);
    private final AiCredentialsService aiCredentials = mock(AiCredentialsService.class);

    private AiPartSearchService service() {
        AiPartSearchService service = new AiPartSearchService(
                mock(DuckDuckGoImageService.class), mock(DuckDuckGoDatasheetService.class),
                specFieldCatalog, aiCredentials, restTemplate, new ObjectMapper());
        // The key and model are the organisation's, resolved per call; which pair does not matter
        // here, only that there is one.
        when(aiCredentials.require()).thenReturn(
                new AiCredentialsService.Credentials("test-key", "claude-haiku-4-5-20251001"));
        when(specFieldCatalog.render()).thenReturn(new SpecFieldCatalog.Fields("\n  - \"package\" (Package)", 1));
        return service;
    }

    private void respondWith(String json) {
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.POST), any(), eq(String.class)))
                .thenReturn(ResponseEntity.ok(json));
    }

    /** Caught before the request, because a bare host is what pasting from a browser bar produces. */
    @Test
    void rejectsAnAddressWithoutAScheme() {
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().searchByUrl("www.mouser.com/ProductDetail/1234"));
        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
    }

    /** The fetch tool's own limit — refusing here costs nothing, letting it through costs a call. */
    @Test
    void rejectsAnAddressLongerThanTheFetchToolAccepts() {
        String tooLong = "https://example.com/" + "x".repeat(250);
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().searchByUrl(tooLong));
        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
    }

    @Test
    void reportsAFailedFetchRatherThanReturningNoResults() {
        respondWith("""
                {"content": [
                  {"type": "server_tool_use", "id": "srv_1", "name": "web_fetch",
                   "input": {"url": "https://example.com/part"}},
                  {"type": "web_fetch_tool_result", "tool_use_id": "srv_1",
                   "content": {"type": "web_fetch_tool_result_error", "error_code": "url_not_accessible"}},
                  {"type": "text", "text": "[]"}
                ], "usage": {"input_tokens": 10, "output_tokens": 2}}
                """);
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().searchByUrl("https://example.com/part"));
        assertEquals(HttpStatus.BAD_GATEWAY, e.getStatusCode());
        assertTrue(e.getReason().contains("Could not read that page"), e.getReason());
        assertTrue(e.getReason().contains("did not return it"), e.getReason());
    }

    /** A successful read parses exactly like a web search result — same contract, same cards. */
    @Test
    void parsesAReadPageIntoTheSameResultAsASearch() {
        respondWith("""
                {"content": [
                  {"type": "server_tool_use", "id": "srv_1", "name": "web_fetch",
                   "input": {"url": "https://example.com/part"}},
                  {"type": "web_fetch_tool_result", "tool_use_id": "srv_1",
                   "content": {"type": "web_fetch_result", "url": "https://example.com/part"}},
                  {"type": "text", "text": "```json\\n[{\\"mpn\\": \\"NE555P\\", \\"manufacturer\\": \\"TI\\", \\"specs\\": [\\"package: DIP-8\\"]}]\\n```"}
                ], "usage": {"input_tokens": 10, "output_tokens": 2}}
                """);
        List<PartSearchResultDTO> results = service().searchByUrl("https://example.com/part");
        assertEquals(1, results.size());
        assertEquals("NE555P", results.get(0).getMpn());
        assertEquals(List.of("package: DIP-8"), results.get(0).getSpecs());
    }
}
