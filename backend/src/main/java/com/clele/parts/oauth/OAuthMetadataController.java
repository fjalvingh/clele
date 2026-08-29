package com.clele.parts.oauth;

import com.clele.parts.config.AppProperties;
import com.clele.parts.service.OAuthClientService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The two discovery documents an MCP client fetches before it can authenticate, at the fixed
 * locations their specifications require.
 *
 * <p><b>They must live at the host root</b>, not under {@code /api} — that is where RFC 9728 and
 * RFC 8414 say to look, and a client will not find them anywhere else. Each is also served under
 * any trailing path, because a client that knows the MCP server as {@code /api/mcp} may insert that
 * path into the well-known URL (RFC 8414 §3.1) — answering both spellings costs one annotation and
 * saves a failed connection nobody could diagnose from the client's error message.
 *
 * <p>Both are public: they are the map, not the door.
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "MCP", description = "OAuth discovery documents for the MCP endpoint")
public class OAuthMetadataController {

    private final OAuthUrls urls;
    private final AppProperties appProperties;

    /** RFC 9728: what this resource is and who issues tokens for it. */
    @GetMapping({"/.well-known/oauth-protected-resource",
                 "/.well-known/oauth-protected-resource/**"})
    @Operation(summary = "OAuth 2.0 Protected Resource Metadata (RFC 9728)")
    public Map<String, Object> protectedResource() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("resource", urls.resource());
        metadata.put("authorization_servers", List.of(urls.issuer()));
        metadata.put("scopes_supported", List.of(OAuthClientService.SCOPE));
        metadata.put("bearer_methods_supported", List.of("header"));
        metadata.put("resource_name", appProperties.getPublicName() + " parts catalogue");
        metadata.put("resource_documentation", urls.base());
        return metadata;
    }

    /** RFC 8414: the endpoints and the flows this app supports as its own authorization server. */
    @GetMapping({"/.well-known/oauth-authorization-server",
                 "/.well-known/oauth-authorization-server/**"})
    @Operation(summary = "OAuth 2.0 Authorization Server Metadata (RFC 8414)")
    public Map<String, Object> authorizationServer() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("issuer", urls.issuer());
        metadata.put("authorization_endpoint", urls.authorizationEndpoint());
        metadata.put("token_endpoint", urls.tokenEndpoint());
        metadata.put("registration_endpoint", urls.registrationEndpoint());
        metadata.put("revocation_endpoint", urls.revocationEndpoint());
        metadata.put("scopes_supported", List.of(OAuthClientService.SCOPE));
        metadata.put("response_types_supported", List.of("code"));
        metadata.put("response_modes_supported", List.of("query"));
        metadata.put("grant_types_supported", List.of("authorization_code", "refresh_token"));
        // OAuth 2.1 removes "plain": a challenge that is its own verifier protects nothing.
        metadata.put("code_challenge_methods_supported", List.of("S256"));
        metadata.put("token_endpoint_auth_methods_supported",
                List.of("none", "client_secret_post", "client_secret_basic"));
        metadata.put("service_documentation", urls.base());
        return metadata;
    }
}
