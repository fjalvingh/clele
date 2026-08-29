package com.clele.parts.config;

import com.clele.parts.service.CurrentOrganisationService;
import com.clele.parts.service.McpApiKeyService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Authenticates {@code /api/mcp} requests from the {@code X-Api-Key} header (or an
 * {@code Authorization: Bearer} one, which is what MCP clients send by default) instead of the
 * session cookie. Stateless, like {@link DaemonApiKeyAuthFilter}: the context is set for this
 * request only and never persisted.
 *
 * <p>It installs two things. The <b>authorities</b> are the owner's, recomputed for the key's
 * organisation — the same live derivation {@link OrganisationAuthoritiesFilter} does for a session,
 * so a permission revoked in the database is revoked for the key on its next call. The
 * <b>organisation</b> is pinned as a request attribute that {@link CurrentOrganisationService}
 * honours ahead of the session, because a stateless request has no session to hold the choice and
 * an MCP client has no way to make one.
 */
@RequiredArgsConstructor
public class McpApiKeyAuthFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";

    private final McpApiKeyService mcpApiKeyService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = token(request);
        if (token != null) {
            mcpApiKeyService.verify(token).ifPresent(key -> {
                List<GrantedAuthority> authorities = key.authorities().stream()
                        .map(a -> (GrantedAuthority) new SimpleGrantedAuthority(a))
                        .toList();
                SecurityContextHolder.getContext().setAuthentication(
                        UsernamePasswordAuthenticationToken.authenticated(
                                key.email(), null, authorities));
                request.setAttribute(CurrentOrganisationService.PINNED_ORGANISATION_ATTRIBUTE,
                        key.organisationId());
            });
        }
        filterChain.doFilter(request, response);
    }

    /** {@code X-Api-Key} first, then a bearer token — MCP clients send one or the other. */
    private static String token(HttpServletRequest request) {
        String header = request.getHeader("X-Api-Key");
        if (header != null && !header.isBlank()) {
            return header.trim();
        }
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.startsWith(BEARER)) {
            return authorization.substring(BEARER.length()).trim();
        }
        return null;
    }
}
