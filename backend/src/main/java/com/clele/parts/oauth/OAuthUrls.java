package com.clele.parts.oauth;

import com.clele.parts.config.AppProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Every absolute URL the OAuth documents have to state, in one place.
 *
 * <p>They must agree exactly — the issuer in the metadata, the {@code resource} a client is told to
 * ask for, and the audience checked on the token that comes back are compared as strings, so a
 * trailing slash or a different host is a failed connection with a puzzling error. Derived like the
 * invitation link: {@code app.base-url} wins when set, otherwise the current request, which is
 * right for a plain deployment and wrong only behind a proxy that rewrites the host.
 */
@Component
@RequiredArgsConstructor
public class OAuthUrls {

    private final AppProperties appProperties;

    /** Origin of this installation, never with a trailing slash. */
    public String base() {
        String configured = appProperties.getBaseUrl();
        String base = (configured == null || configured.isBlank())
                ? ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString()
                : configured;
        return base.replaceAll("/+$", "");
    }

    /** The authorization server's issuer identifier (RFC 8414) — this app is its own. */
    public String issuer() {
        return base();
    }

    /** The canonical URI of the MCP server (RFC 8707), and the audience of every token issued. */
    public String resource() {
        return base() + "/api/mcp";
    }

    public String protectedResourceMetadata() {
        return base() + "/.well-known/oauth-protected-resource";
    }

    public String authorizationEndpoint() {
        return base() + "/api/oauth/authorize";
    }

    public String tokenEndpoint() {
        return base() + "/api/oauth/token";
    }

    public String registrationEndpoint() {
        return base() + "/api/oauth/register";
    }

    public String revocationEndpoint() {
        return base() + "/api/oauth/revoke";
    }

    /** Where the browser is sent to approve a request: a route in the SPA, not an endpoint. */
    public String consentPage(String requestId) {
        return base() + "/oauth/consent?request=" + requestId;
    }

    /**
     * Whether a {@code resource} value names this server. Clients differ in how specific they are —
     * some send the endpoint, some the origin — and both identify us, so both are accepted. Anything
     * else is a token meant for somewhere else and must never be honoured here.
     */
    public boolean isOurResource(String resource) {
        if (resource == null || resource.isBlank()) return true;   // absent: nothing to contradict
        String normalised = resource.replaceAll("/+$", "");
        return normalised.equalsIgnoreCase(resource()) || normalised.equalsIgnoreCase(base());
    }
}
