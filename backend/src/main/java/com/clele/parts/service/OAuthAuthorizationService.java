package com.clele.parts.service;

import com.clele.parts.dto.OAuthConsentDTO;
import com.clele.parts.model.AppUser;
import com.clele.parts.model.OAuthAuthorization;
import com.clele.parts.model.OAuthClient;
import com.clele.parts.model.Organisation;
import com.clele.parts.oauth.OAuthException;
import com.clele.parts.oauth.OAuthTokens;
import com.clele.parts.oauth.OAuthUrls;
import com.clele.parts.repository.OAuthAuthorizationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * The authorization endpoint and the consent it depends on.
 *
 * <p>The shape of this flow is decided by one rule: <b>an error may only be sent to a redirect URI
 * that has already been proved to belong to the client.</b> Everything checked before that point —
 * the client id, the redirect URI itself — fails with a page the user sees; everything after fails
 * by redirecting with {@code ?error=…}, which is what the client is waiting for. Getting that
 * backwards turns this endpoint into an open redirector.
 *
 * <p>Approval is what grants access, not registration: until a logged-in user approves, a
 * registered client holds nothing but an identifier.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class OAuthAuthorizationService {

    /** How long the user has to complete the consent screen. */
    private static final Duration REQUEST_TTL = Duration.ofMinutes(10);

    /** How long the issued code stays exchangeable — one browser redirect, no more. */
    private static final Duration CODE_TTL = Duration.ofMinutes(5);

    private final OAuthAuthorizationRepository authorizationRepository;
    private final OAuthClientService clientService;
    private final OrganisationService organisationService;
    private final CurrentUserService currentUserService;
    private final CurrentOrganisationService currentOrganisationService;
    private final OAuthUrls urls;

    /**
     * Validate an authorization request and park it. Returns where to send the browser: the SPA's
     * consent screen, which is the only thing that can turn this into a code.
     */
    public String begin(String responseType, String clientId, String redirectUri, String scope,
                        String state, String codeChallenge, String codeChallengeMethod,
                        String resource) {
        OAuthClient client = clientService.require(clientId);

        // Before this line, nothing may be redirected anywhere.
        if (redirectUri == null || redirectUri.isBlank()) {
            throw OAuthException.shown("invalid_request", "redirect_uri is required");
        }
        if (!client.redirectUriList().contains(redirectUri)) {
            log.warn("OAuth client {} asked to redirect to an unregistered URI: {}",
                    clientId, redirectUri);
            throw OAuthException.shown("invalid_request",
                    "That redirect_uri is not registered for this client");
        }
        // After it, the client's own redirect URI is the right place to report a problem.
        if (!"code".equals(responseType)) {
            throw OAuthException.redirected("unsupported_response_type",
                    "Only the authorization code flow is supported", redirectUri, state);
        }
        if (codeChallenge == null || codeChallenge.isBlank()) {
            throw OAuthException.redirected("invalid_request",
                    "PKCE is required: code_challenge is missing", redirectUri, state);
        }
        if (!"S256".equals(codeChallengeMethod)) {
            throw OAuthException.redirected("invalid_request",
                    "code_challenge_method must be S256", redirectUri, state);
        }
        if (!urls.isOurResource(resource)) {
            throw OAuthException.redirected("invalid_target",
                    "This server issues tokens for " + urls.resource() + " only", redirectUri, state);
        }

        OAuthAuthorization request = authorizationRepository.save(OAuthAuthorization.builder()
                .requestId(OAuthTokens.random())
                .client(client)
                .redirectUri(redirectUri)
                // Unknown scopes are narrowed rather than refused: this server has exactly one, and
                // a client asking for more than exists should still get what it can have.
                .scope(OAuthClientService.SCOPE)
                .state(state)
                .resource(resource)
                .codeChallenge(codeChallenge)
                .codeChallengeMethod(codeChallengeMethod)
                .expiresAt(LocalDateTime.now().plus(REQUEST_TTL))
                .build());

        log.info("OAuth authorization request {} from client {} ('{}')",
                request.getRequestId(), clientId, client.getClientName());
        return urls.consentPage(request.getRequestId());
    }

    /** What the consent screen needs to show. Requires a logged-in user — that is the whole point. */
    @Transactional(readOnly = true)
    public OAuthConsentDTO consent(String requestId) {
        OAuthAuthorization request = requirePending(requestId);
        List<Organisation> selectable = currentOrganisationService.selectable();
        return OAuthConsentDTO.builder()
                .requestId(request.getRequestId())
                .clientName(request.getClient().getClientName())
                .redirectHost(hostOf(request.getRedirectUri()))
                .scope(request.getScope())
                .organisations(selectable.stream().map(organisationService::toDTO).toList())
                .defaultOrganisationId(currentOrganisationService.currentId())
                .build();
    }

    /** Approve: mint the code and hand back where the browser goes next. */
    public String approve(String requestId, Long organisationId) {
        AppUser me = currentUserService.current();
        OAuthAuthorization request = requirePending(requestId);

        Long chosen = organisationId != null ? organisationId : currentOrganisationService.currentId();
        if (!currentOrganisationService.isSelectable(chosen)) {
            throw OAuthException.shown("access_denied",
                    "You are not a member of that organisation", HttpStatus.FORBIDDEN);
        }

        String code = OAuthTokens.random();
        request.setUser(me);
        request.setOrganisation(organisationService.get(chosen));
        request.setCodeHash(OAuthTokens.hash(code));
        request.setExpiresAt(LocalDateTime.now().plus(CODE_TTL));

        log.info("OAuth request {} approved by {} for organisation {}",
                requestId, me.getEmail(), chosen);
        return back(request.getRedirectUri(), "code", code, request.getState());
    }

    /** Refuse: the client is told so at its redirect URI, which is how it stops waiting. */
    public String deny(String requestId) {
        OAuthAuthorization request = requirePending(requestId);
        request.setConsumedAt(LocalDateTime.now());
        log.info("OAuth request {} denied", requestId);
        return back(request.getRedirectUri(), "error", "access_denied", request.getState());
    }

    private OAuthAuthorization requirePending(String requestId) {
        OAuthAuthorization request = authorizationRepository.findByRequestId(requestId)
                .orElseThrow(() -> OAuthException.shown("invalid_request",
                        "This authorization request is not valid", HttpStatus.NOT_FOUND));
        if (request.getConsumedAt() != null || request.getCodeHash() != null) {
            throw OAuthException.shown("invalid_request",
                    "This authorization request has already been answered", HttpStatus.CONFLICT);
        }
        if (request.isExpired()) {
            throw OAuthException.shown("invalid_request",
                    "This authorization request has expired — start again from your client",
                    HttpStatus.GONE);
        }
        return request;
    }

    /** The client's redirect URI with one parameter added, and its own {@code state} returned. */
    private static String back(String redirectUri, String key, String value, String state) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(redirectUri)
                .queryParam(key, value);
        if (state != null && !state.isBlank()) {
            builder.queryParam("state", state);
        }
        return builder.encode().build().toUriString();
    }

    private static String hostOf(String uri) {
        try {
            URI parsed = URI.create(uri);
            return parsed.getHost() == null ? uri : parsed.getHost();
        } catch (IllegalArgumentException e) {
            return uri;
        }
    }
}
