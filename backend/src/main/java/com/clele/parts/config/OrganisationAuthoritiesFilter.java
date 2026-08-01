package com.clele.parts.config;

import com.clele.parts.model.AppUser;
import com.clele.parts.model.Organisation;
import com.clele.parts.repository.AppUserRepository;
import com.clele.parts.service.CurrentOrganisationService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Recomputes the request's granted authorities from the database, for the organisation currently
 * in force, on every authenticated request.
 *
 * <p>Without this, authorities are whatever was computed when the session was created. Sessions
 * live for a 7-day sliding window, so a permission granted, revoked — or <em>introduced by a
 * migration</em> — stayed invisible until the user happened to log in again. That last case is not
 * hypothetical: {@code ORG_ADMIN} did not exist when older sessions were created, so those sessions
 * could never carry it, and their holders were denied the screens they administer while
 * {@code /auth/me} (which reads permissions live) happily showed the navigation for them.
 *
 * <p>The recomputed context is deliberately <b>not</b> saved back to the
 * {@code SecurityContextRepository}: it is derived state, valid for this request and this
 * organisation only. Persisting it would just re-freeze it. {@code PermissionService.applyAuthorities}
 * still runs at login and on an organisation switch, because those requests build their response
 * <em>after</em> this filter has already passed.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class OrganisationAuthoritiesFilter extends OncePerRequestFilter {

    private final AppUserRepository userRepository;
    private final CurrentOrganisationService currentOrganisationService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        currentAuthentication()
                .flatMap(this::loadUser)
                .ifPresent(user -> applyFor(user, request));
        chain.doFilter(request, response);
    }

    /**
     * Global permissions come straight from the account; the per-organisation set needs an
     * organisation, which {@link CurrentOrganisationService} resolves from the session. That
     * resolution consults the authorities itself (a Global Administrator may select any
     * organisation), so the global-only set is installed first and the full set replaces it.
     */
    private void applyFor(AppUser user, HttpServletRequest request) {
        Authentication original = SecurityContextHolder.getContext().getAuthentication();
        setAuthorities(original, user.getPermissions());

        Set<String> authorities = new LinkedHashSet<>(user.getPermissions());
        try {
            Organisation organisation = currentOrganisationService.current();
            authorities.addAll(user.permissionsIn(organisation.getId()));
        } catch (RuntimeException e) {
            // No organisation in force (a user belonging to none, or the session cannot be
            // resolved). Global permissions still apply; the request fails later on its own terms
            // if it needed an organisation.
            log.debug("No organisation in force for {}: {}", user.getEmail(), e.toString());
        }
        setAuthorities(original, authorities);
    }

    private void setAuthorities(Authentication original, Set<String> permissions) {
        List<GrantedAuthority> granted = permissions.stream()
                .map(p -> (GrantedAuthority) new SimpleGrantedAuthority(p))
                .toList();
        Authentication refreshed = UsernamePasswordAuthenticationToken.authenticated(
                original.getPrincipal(), original.getCredentials(), granted);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(refreshed);
        SecurityContextHolder.setContext(context);
    }

    private Optional<Authentication> currentAuthentication() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()
                || auth instanceof AnonymousAuthenticationToken
                || auth.getName() == null) {
            return Optional.empty();
        }
        return Optional.of(auth);
    }

    private Optional<AppUser> loadUser(Authentication auth) {
        return userRepository.findByEmail(auth.getName().trim().toLowerCase());
    }
}
