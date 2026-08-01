package com.clele.parts.service;

import com.clele.parts.model.AppUser;
import com.clele.parts.model.Organisation;
import com.clele.parts.model.Permissions;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Turns a user's permissions into Spring Security authorities for the organisation currently in
 * force, and keeps the session's authorities in step when that organisation changes.
 *
 * <p>This is what makes the existing {@code @PreAuthorize("hasAuthority('PARTS_EDIT')")}
 * annotations organisation-aware without touching any of them: the authority set is simply
 * recomputed and re-saved whenever the answer could change — at login and on every organisation
 * switch. A user who is {@code PARTS_EDIT} in one organisation and not in another gains and loses
 * the authority as they switch.
 *
 * <p>Consequence worth knowing: changing a user's permissions does not affect their <em>current</em>
 * session — the new rights apply after their next switch or login.
 */
@Service
@RequiredArgsConstructor
public class PermissionService {

    private final SecurityContextRepository securityContextRepository;

    /**
     * The authorities a user has while working in {@code organisation}: their global permissions
     * plus the permissions they hold in that organisation (all of them, for a Global Administrator).
     */
    public Set<String> authoritiesFor(AppUser user, Organisation organisation) {
        Set<String> authorities = new LinkedHashSet<>(user.getPermissions());
        if (organisation != null) {
            authorities.addAll(user.permissionsIn(organisation.getId()));
        }
        return authorities;
    }

    /**
     * Re-issue the session's {@link Authentication} with the authorities for {@code organisation}.
     * Saving through the {@link SecurityContextRepository} is required in Spring Security 6 —
     * mutating the held context is not persisted on its own.
     */
    public void applyAuthorities(AppUser user, Organisation organisation,
                                 HttpServletRequest request, HttpServletResponse response) {
        List<GrantedAuthority> authorities = authoritiesFor(user, organisation).stream()
                .map(a -> (GrantedAuthority) new SimpleGrantedAuthority(a))
                .toList();

        Authentication current = SecurityContextHolder.getContext().getAuthentication();
        Object principal = current != null ? current.getPrincipal() : user.getEmail();

        Authentication refreshed =
                UsernamePasswordAuthenticationToken.authenticated(principal, null, authorities);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(refreshed);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);
    }

    /** Throw 403 unless the current authentication carries {@code permission}. */
    public void require(String permission) {
        if (!has(permission)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "You don't have permission to do that");
        }
    }

    public boolean has(String permission) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return false;
        return auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(permission::equals);
    }

    public boolean isGlobalAdmin() {
        return has(Permissions.GLOBAL_ADMIN);
    }

    /** Throw 403 unless the current user is an Organisation Admin of the organisation in force. */
    public void requireOrgAdmin() {
        require(Permissions.ORG_ADMIN);
    }

    /** Throw 403 unless the current user is a Global Administrator. */
    public void requireGlobalAdmin() {
        require(Permissions.GLOBAL_ADMIN);
    }
}
