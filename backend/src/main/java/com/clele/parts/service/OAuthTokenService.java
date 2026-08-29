package com.clele.parts.service;

import com.clele.parts.dto.OAuthTokenResponse;
import com.clele.parts.mcp.McpPrincipal;
import com.clele.parts.model.AppUser;
import com.clele.parts.model.OAuthAuthorization;
import com.clele.parts.model.OAuthClient;
import com.clele.parts.model.OAuthToken;
import com.clele.parts.oauth.OAuthException;
import com.clele.parts.oauth.OAuthTokens;
import com.clele.parts.oauth.OAuthUrls;
import com.clele.parts.repository.OAuthAuthorizationRepository;
import com.clele.parts.repository.OAuthTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * The token endpoint, and the check that runs on every MCP call afterwards.
 *
 * <p>Two rules here are worth stating because getting either wrong is silent:
 *
 * <ul>
 *   <li><b>A code is single-use, and a second presentation is treated as an attack.</b> If a code
 *       comes back after it has been exchanged, one of the two presenters is not the client, and
 *       there is no way to tell which — so the tokens that code already produced are revoked as
 *       well as the request refused.</li>
 *   <li><b>A refresh token is rotated, and reuse of an old one revokes the family.</b> Public
 *       clients cannot keep a secret, so rotation is what limits a stolen refresh token to a single
 *       use before the theft becomes visible.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class OAuthTokenService {

    private static final Duration ACCESS_TTL = Duration.ofHours(1);
    private static final Duration REFRESH_TTL = Duration.ofDays(30);
    /** Same floor as the API key's: a read must not turn into a write on every call. */
    private static final Duration TOUCH_INTERVAL = Duration.ofMinutes(1);

    private final OAuthTokenRepository tokenRepository;
    private final OAuthAuthorizationRepository authorizationRepository;
    private final OAuthClientService clientService;
    private final OAuthRevocationService revocationService;
    private final OrganisationService organisationService;
    private final OAuthUrls urls;

    /** Exchange an authorization code (OAuth 2.1 §4.1.3), PKCE verifier and all. */
    public OAuthTokenResponse exchangeCode(String code, String redirectUri, String clientId,
                                           String codeVerifier, String clientSecret) {
        if (code == null || code.isBlank()) {
            throw OAuthException.shown("invalid_request", "code is required");
        }
        OAuthAuthorization request = authorizationRepository.findByCodeHash(OAuthTokens.hash(code))
                .orElseThrow(() -> OAuthException.shown("invalid_grant",
                        "Unknown or expired authorization code"));

        if (request.getConsumedAt() != null) {
            // Replay. Whoever holds the code now, the tokens it produced can no longer be trusted.
            // Revoked in its own transaction, because the exception below would otherwise roll the
            // revocation back and leave the stolen token working -- see OAuthRevocationService.
            int revoked = revocationService.revokeIssuedBy(request.getId());
            log.warn("Authorization code for client {} replayed; revoked {} token(s)",
                    request.getClient().getClientId(), revoked);
            throw OAuthException.shown("invalid_grant", "This authorization code has already been used");
        }
        if (request.isExpired()) {
            throw OAuthException.shown("invalid_grant", "This authorization code has expired");
        }

        OAuthClient client = authenticateClient(clientId, clientSecret);
        if (!client.getClientId().equals(request.getClient().getClientId())) {
            throw OAuthException.shown("invalid_grant", "This code was issued to another client");
        }
        if (redirectUri == null || !redirectUri.equals(request.getRedirectUri())) {
            throw OAuthException.shown("invalid_grant",
                    "redirect_uri does not match the one the code was issued for");
        }
        if (codeVerifier == null || codeVerifier.isBlank()
                || !OAuthTokens.s256(codeVerifier).equals(request.getCodeChallenge())) {
            throw OAuthException.shown("invalid_grant", "PKCE verification failed");
        }

        request.setConsumedAt(LocalDateTime.now());
        String audience = (request.getResource() == null || request.getResource().isBlank())
                ? urls.resource()
                : request.getResource();
        return issue(client, request.getUser(), request.getOrganisation().getId(),
                request.getScope(), audience, request);
    }

    /** Refresh, with rotation (OAuth 2.1 §4.3.1) and reuse detection. */
    public OAuthTokenResponse refresh(String refreshToken, String clientId, String clientSecret) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw OAuthException.shown("invalid_request", "refresh_token is required");
        }
        OAuthToken existing = tokenRepository.findByRefreshTokenHash(OAuthTokens.hash(refreshToken))
                .orElseThrow(() -> OAuthException.shown("invalid_grant", "Unknown refresh token"));

        OAuthClient client = authenticateClient(clientId, clientSecret);
        if (!client.getClientId().equals(existing.getClient().getClientId())) {
            throw OAuthException.shown("invalid_grant", "That refresh token belongs to another client");
        }
        if (existing.getRevokedAt() != null) {
            // A rotated-away token coming back means someone kept a copy. End the whole family --
            // again in its own transaction, so the rejection below cannot undo it.
            int revoked = revocationService.revokeFamily(client.getClientId(), existing.getUser().getId());
            log.warn("Reused refresh token for client {}; revoked {} live token(s)",
                    client.getClientId(), revoked);
            throw OAuthException.shown("invalid_grant", "That refresh token has already been used");
        }
        if (existing.getRefreshExpiresAt() == null
                || existing.getRefreshExpiresAt().isBefore(LocalDateTime.now())) {
            throw OAuthException.shown("invalid_grant", "That refresh token has expired");
        }

        existing.setRevokedAt(LocalDateTime.now());
        return issue(client, existing.getUser(), existing.getOrganisation().getId(),
                existing.getScope(), existing.getAudience(), existing.getAuthorization());
    }

    /** RFC 7009. Revoking something already gone is a success, by design. */
    public void revoke(String token) {
        if (token == null || token.isBlank()) return;
        String hash = OAuthTokens.hash(token);
        tokenRepository.findByAccessTokenHash(hash)
                .or(() -> tokenRepository.findByRefreshTokenHash(hash))
                .ifPresent(found -> found.setRevokedAt(LocalDateTime.now()));
    }

    /**
     * Verify a bearer token presented at {@code /api/mcp}.
     *
     * <p>Empty for anything that does not check out — unknown, expired, revoked, issued for another
     * audience, or held by someone who has since lost the organisation it was granted against. The
     * membership re-check is the same one the API key does, for the same reason: a stored credential
     * must not outlive the access it was granted under.
     */
    public Optional<McpPrincipal> verify(String accessToken) {
        if (accessToken == null || accessToken.isBlank()) return Optional.empty();
        Optional<OAuthToken> found = tokenRepository.findByAccessTokenHash(OAuthTokens.hash(accessToken));
        if (found.isEmpty()) return Optional.empty();

        OAuthToken token = found.get();
        if (!token.isLive()) return Optional.empty();
        if (!urls.isOurResource(token.getAudience())) {
            log.warn("OAuth token {} presented here but issued for audience {}",
                    token.getId(), token.getAudience());
            return Optional.empty();
        }

        AppUser user = token.getUser();
        Long organisationId = token.getOrganisation().getId();
        boolean member = user.isGlobalAdmin() || user.getOrganisations().stream()
                .anyMatch(organisation -> organisation.getId().equals(organisationId));
        if (!member) {
            log.warn("OAuth token {} refused: {} is no longer a member of organisation {}",
                    token.getId(), user.getEmail(), organisationId);
            return Optional.empty();
        }

        touch(token);
        Set<String> authorities = new LinkedHashSet<>(user.getPermissions());
        authorities.addAll(user.permissionsIn(organisationId));
        return Optional.of(new McpPrincipal(user.getEmail(), organisationId, authorities));
    }

    private OAuthTokenResponse issue(OAuthClient client, AppUser user, Long organisationId,
                                     String scope, String audience, OAuthAuthorization from) {
        String accessToken = OAuthTokens.random();
        String refreshToken = OAuthTokens.random();
        LocalDateTime now = LocalDateTime.now();

        tokenRepository.save(OAuthToken.builder()
                .accessTokenHash(OAuthTokens.hash(accessToken))
                .refreshTokenHash(OAuthTokens.hash(refreshToken))
                .client(client)
                .authorization(from)
                .user(user)
                .organisation(organisationService.get(organisationId))
                .scope(scope)
                .audience(audience)
                .expiresAt(now.plus(ACCESS_TTL))
                .refreshExpiresAt(now.plus(REFRESH_TTL))
                .build());
        client.setLastUsedAt(now);

        return OAuthTokenResponse.builder()
                .accessToken(accessToken)
                .tokenType("Bearer")
                .expiresIn(ACCESS_TTL.toSeconds())
                .refreshToken(refreshToken)
                .scope(scope)
                .build();
    }

    /**
     * A public client is identified by its {@code client_id} and proved by PKCE; a confidential one
     * must also present its secret. A public client that sends a secret is not treated as an error —
     * it has none registered, so there is nothing for it to prove.
     */
    private OAuthClient authenticateClient(String clientId, String clientSecret) {
        OAuthClient client = clientService.require(clientId);
        if (client.isConfidential() && !clientService.secretMatches(client, clientSecret)) {
            throw OAuthException.shown("invalid_client", "Client authentication failed",
                    HttpStatus.UNAUTHORIZED);
        }
        return client;
    }

    private void touch(OAuthToken token) {
        LocalDateTime now = LocalDateTime.now();
        if (token.getLastUsedAt() == null || token.getLastUsedAt().isBefore(now.minus(TOUCH_INTERVAL))) {
            token.setLastUsedAt(now);
        }
    }
}
