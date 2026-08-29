package com.clele.parts.oauth;

import com.clele.parts.dto.OAuthApproveRequest;
import com.clele.parts.dto.OAuthConsentDTO;
import com.clele.parts.dto.OAuthRegistrationRequest;
import com.clele.parts.dto.OAuthRegistrationResponse;
import com.clele.parts.dto.OAuthTokenResponse;
import com.clele.parts.service.OAuthAuthorizationService;
import com.clele.parts.service.OAuthClientService;
import com.clele.parts.service.OAuthTokenService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/**
 * The OAuth endpoints themselves. Only the discovery documents have a fixed location
 * ({@link OAuthMetadataController}); these live under {@code /api} with everything else.
 *
 * <p>Three of them are public because they have to be — a client registering, a browser arriving to
 * log in, a client exchanging a code has no session by definition. The consent endpoints are the
 * opposite: they are the point at which a <em>logged-in user</em> decides, so they sit on the
 * ordinary session chain and would be meaningless without it.
 */
@RestController
@RequestMapping("/api/oauth")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "MCP", description = "OAuth authorization for the MCP endpoint")
public class OAuthController {

    private final OAuthClientService clientService;
    private final OAuthAuthorizationService authorizationService;
    private final OAuthTokenService tokenService;
    private final OAuthUrls urls;

    /** RFC 7591. Public: this is how a client that has never been heard of gets an id. */
    @PostMapping(value = "/register", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Dynamic client registration (RFC 7591)")
    public ResponseEntity<OAuthRegistrationResponse> register(
            @RequestBody OAuthRegistrationRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(clientService.register(request));
    }

    /**
     * Where the client sends the browser. Ends in a redirect every time — to the consent screen when
     * the request is sound, back to the client with an error when it is the client's fault, and to
     * the consent screen carrying an error when it cannot safely be reported anywhere else.
     */
    @GetMapping("/authorize")
    @Operation(summary = "Authorization endpoint (OAuth 2.1 authorization code + PKCE)")
    public ResponseEntity<Void> authorize(
            @RequestParam(name = "response_type", required = false) String responseType,
            @RequestParam(name = "client_id", required = false) String clientId,
            @RequestParam(name = "redirect_uri", required = false) String redirectUri,
            @RequestParam(name = "scope", required = false) String scope,
            @RequestParam(name = "state", required = false) String state,
            @RequestParam(name = "code_challenge", required = false) String codeChallenge,
            @RequestParam(name = "code_challenge_method", required = false) String codeChallengeMethod,
            @RequestParam(name = "resource", required = false) String resource) {
        try {
            String consentPage = authorizationService.begin(responseType, clientId, redirectUri,
                    scope, state, codeChallenge, codeChallengeMethod, resource);
            return redirect(consentPage);
        } catch (OAuthException e) {
            return redirect(e.isRedirectable() ? backToClient(e) : errorPage(e));
        }
    }

    /** What the consent screen shows. Session-authenticated: the user is the one deciding. */
    @GetMapping("/consent/{requestId}")
    @Operation(summary = "Details of a pending authorization request, for the consent screen")
    public OAuthConsentDTO consent(@PathVariable String requestId) {
        return authorizationService.consent(requestId);
    }

    @PostMapping("/consent/{requestId}/approve")
    @Operation(summary = "Approve a pending authorization request")
    public Map<String, String> approve(@PathVariable String requestId,
                                       @RequestBody(required = false) OAuthApproveRequest request) {
        Long organisationId = request == null ? null : request.getOrganisationId();
        return Map.of("redirectUri", authorizationService.approve(requestId, organisationId));
    }

    @PostMapping("/consent/{requestId}/deny")
    @Operation(summary = "Refuse a pending authorization request")
    public Map<String, String> deny(@PathVariable String requestId) {
        return Map.of("redirectUri", authorizationService.deny(requestId));
    }

    /** OAuth 2.1 §4.1.3 / §4.3. Form-encoded, as the specification requires. */
    @PostMapping(value = "/token", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    @Operation(summary = "Token endpoint (authorization_code and refresh_token grants)")
    public OAuthTokenResponse token(
            @RequestParam(name = "grant_type", required = false) String grantType,
            @RequestParam(name = "code", required = false) String code,
            @RequestParam(name = "redirect_uri", required = false) String redirectUri,
            @RequestParam(name = "client_id", required = false) String clientId,
            @RequestParam(name = "client_secret", required = false) String clientSecret,
            @RequestParam(name = "code_verifier", required = false) String codeVerifier,
            @RequestParam(name = "refresh_token", required = false) String refreshToken,
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        BasicCredentials basic = BasicCredentials.parse(authorization);
        String effectiveClientId = basic != null ? basic.clientId() : clientId;
        String effectiveSecret = basic != null ? basic.secret() : clientSecret;

        if ("authorization_code".equals(grantType)) {
            return tokenService.exchangeCode(code, redirectUri, effectiveClientId, codeVerifier,
                    effectiveSecret);
        }
        if ("refresh_token".equals(grantType)) {
            return tokenService.refresh(refreshToken, effectiveClientId, effectiveSecret);
        }
        throw OAuthException.shown("unsupported_grant_type",
                "grant_type must be authorization_code or refresh_token");
    }

    /** RFC 7009. Revoking an unknown token is a success — the caller's goal is already met. */
    @PostMapping(value = "/revoke", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    @Operation(summary = "Token revocation (RFC 7009)")
    public ResponseEntity<Void> revoke(@RequestParam(name = "token", required = false) String token) {
        tokenService.revoke(token);
        return ResponseEntity.ok().build();
    }

    /**
     * OAuth errors are reported in the specification's shape, not this app's usual {@code error}
     * envelope: a client parses {@code error} / {@code error_description} and acts on the code.
     */
    @ExceptionHandler(OAuthException.class)
    public ResponseEntity<Map<String, String>> handle(OAuthException e) {
        log.info("OAuth error: {} ({})", e.getError(), e.getDescription());
        return ResponseEntity.status(e.getStatus())
                .body(Map.of("error", e.getError(), "error_description", e.getDescription()));
    }

    private static ResponseEntity<Void> redirect(String location) {
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(location)).build();
    }

    private static String backToClient(OAuthException e) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(e.getRedirectUri())
                .queryParam("error", e.getError())
                .queryParam("error_description", e.getDescription());
        if (e.getState() != null && !e.getState().isBlank()) {
            builder.queryParam("state", e.getState());
        }
        return builder.encode().build().toUriString();
    }

    /**
     * An error that must not be sent to the client is shown in the app itself — the consent screen
     * renders it. Hand-rolling an HTML page here would mean a second place that has to look like
     * Sortiment and a second place to escape attacker-supplied text.
     */
    private String errorPage(OAuthException e) {
        return UriComponentsBuilder.fromUriString(urls.base() + "/oauth/consent")
                .queryParam("error", e.getError())
                .queryParam("error_description", e.getDescription())
                .encode().build().toUriString();
    }

    /** {@code Authorization: Basic base64(client_id:client_secret)} — client_secret_basic. */
    private record BasicCredentials(String clientId, String secret) {
        static BasicCredentials parse(String header) {
            if (header == null || !header.regionMatches(true, 0, "Basic ", 0, 6)) return null;
            try {
                String decoded = new String(Base64.getDecoder().decode(header.substring(6).trim()),
                        StandardCharsets.UTF_8);
                int separator = decoded.indexOf(':');
                if (separator < 0) return null;
                return new BasicCredentials(decoded.substring(0, separator),
                        decoded.substring(separator + 1));
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }
}
