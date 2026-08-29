package com.clele.parts.service;

import com.clele.parts.dto.OAuthRegistrationRequest;
import com.clele.parts.dto.OAuthRegistrationResponse;
import com.clele.parts.model.OAuthClient;
import com.clele.parts.oauth.OAuthException;
import com.clele.parts.repository.OAuthClientRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.URISyntaxException;
import java.security.SecureRandom;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Dynamic client registration (RFC 7591) — the reason an MCP client can be pointed at this server
 * with a URL and nothing else.
 *
 * <p><b>Registration is open and grants nothing.</b> Anyone can register a client; what they get is
 * an identifier and the right to <em>ask</em>. Access begins only when a logged-in user approves
 * that client in the browser, and is limited to what that user can see. This is why an open
 * registration endpoint is not the hole it first looks like — and why the consent screen must show
 * the client's self-declared name as a claim rather than an identity.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class OAuthClientService {

    /** The only scope this server issues: read the catalogue. */
    public static final String SCOPE = "mcp:read";

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Set<String> SUPPORTED_GRANTS = Set.of("authorization_code", "refresh_token");
    private static final Set<String> LOOPBACK = Set.of("localhost", "127.0.0.1", "[::1]", "::1");

    private final OAuthClientRepository clientRepository;
    private final PasswordEncoder passwordEncoder;

    public OAuthRegistrationResponse register(OAuthRegistrationRequest request) {
        List<String> redirectUris = request.getRedirectUris();
        if (redirectUris == null || redirectUris.isEmpty()) {
            throw OAuthException.shown("invalid_redirect_uri", "At least one redirect_uri is required");
        }
        redirectUris.forEach(OAuthClientService::validateRedirectUri);

        // A client that does not say how it will authenticate is public: that is the common case
        // here (a desktop app), and PKCE is what protects it. Anything else gets a secret.
        String authMethod = request.getTokenEndpointAuthMethod() == null
                ? "none"
                : request.getTokenEndpointAuthMethod().trim().toLowerCase(Locale.ROOT);
        if (!Set.of("none", "client_secret_post", "client_secret_basic").contains(authMethod)) {
            throw OAuthException.shown("invalid_client_metadata",
                    "Unsupported token_endpoint_auth_method: " + authMethod);
        }

        List<String> grants = (request.getGrantTypes() == null || request.getGrantTypes().isEmpty())
                ? List.of("authorization_code", "refresh_token")
                : request.getGrantTypes().stream()
                        .map(g -> g.trim().toLowerCase(Locale.ROOT))
                        .filter(SUPPORTED_GRANTS::contains)
                        .toList();
        if (!grants.contains("authorization_code")) {
            throw OAuthException.shown("invalid_client_metadata",
                    "Only the authorization_code grant (with refresh_token) is supported");
        }

        String secret = "none".equals(authMethod) ? null : randomToken();
        OAuthClient client = clientRepository.save(OAuthClient.builder()
                .clientId(randomToken())
                .clientName(trimToLength(request.getClientName(), 200))
                .redirectUris(String.join("\n", redirectUris))
                .grantTypes(String.join(" ", grants))
                .scope(SCOPE)
                .tokenEndpointAuthMethod(authMethod)
                .clientSecretHash(secret == null ? null : passwordEncoder.encode(secret))
                .build());

        log.info("Registered OAuth client {} ('{}'), redirect {}",
                client.getClientId(), client.getClientName(), redirectUris);

        return OAuthRegistrationResponse.builder()
                .clientId(client.getClientId())
                .clientIdIssuedAt(client.getCreatedAt().toEpochSecond(ZoneOffset.UTC))
                .clientSecret(secret)
                .clientSecretExpiresAt(secret == null ? null : 0L)
                .redirectUris(redirectUris)
                .clientName(client.getClientName())
                .grantTypes(grants)
                .responseTypes(List.of("code"))
                .tokenEndpointAuthMethod(authMethod)
                .scope(SCOPE)
                .build();
    }

    public OAuthClient require(String clientId) {
        if (clientId == null || clientId.isBlank()) {
            throw OAuthException.shown("invalid_client", "client_id is required");
        }
        return clientRepository.findById(clientId)
                .orElseThrow(() -> OAuthException.shown("invalid_client", "Unknown client"));
    }

    /** Check a secret against a confidential client's hash. Public clients present none. */
    public boolean secretMatches(OAuthClient client, String secret) {
        return client.isConfidential() && secret != null
                && passwordEncoder.matches(secret, client.getClientSecretHash());
    }

    /**
     * A redirect URI must be somewhere the authorization code can only land in the right hands:
     * HTTPS, or loopback for a desktop app that listens on a random local port. A custom
     * application scheme ({@code myapp://…}) is allowed for the same reason — the operating system
     * routes it to an installed app, not across a network. Plain {@code http://} to anywhere else
     * would put the code on the wire in clear.
     */
    private static void validateRedirectUri(String value) {
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException e) {
            throw OAuthException.shown("invalid_redirect_uri", "Not a valid URI: " + value);
        }
        if (!uri.isAbsolute() || uri.getFragment() != null) {
            throw OAuthException.shown("invalid_redirect_uri",
                    "redirect_uri must be absolute and carry no fragment: " + value);
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if ("https".equals(scheme)) return;
        if ("http".equals(scheme)) {
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            if (LOOPBACK.contains(host)) return;
            throw OAuthException.shown("invalid_redirect_uri",
                    "http is only allowed for loopback addresses: " + value);
        }
        // A private scheme: the OS hands it to an installed application.
    }

    private static String randomToken() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String trimToLength(String value, int max) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }
}
