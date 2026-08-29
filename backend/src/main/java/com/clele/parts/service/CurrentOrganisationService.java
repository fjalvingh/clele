package com.clele.parts.service;

import com.clele.parts.model.AppUser;
import com.clele.parts.model.Organisation;
import com.clele.parts.model.Permissions;
import com.clele.parts.repository.AppUserRepository;
import com.clele.parts.repository.OrganisationRepository;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Resolves the organisation in force for the current request. Every read and write in the
 * application is scoped to it, so this is the tenancy equivalent of {@link CurrentUserService}.
 *
 * <p>The choice lives in the HTTP session (attribute {@link #SESSION_ATTRIBUTE}), which Spring
 * Session persists to PostgreSQL, and falls back to {@code app_user.last_organisation_id} — updated
 * on every switch — so a fresh login lands back where the user left off.
 *
 * <p>There is deliberately no fallback for code running outside a request (background jobs, the
 * Partsbox importer): those must resolve and pass an organisation explicitly rather than silently
 * operating on someone's session state.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CurrentOrganisationService {

    static final String SESSION_ATTRIBUTE = "currentOrganisationId";

    /**
     * Request attribute naming the organisation a stateless request is pinned to, set by
     * {@code McpApiKeyAuthFilter} from the API key it authenticated. Consulted ahead of the
     * session: an MCP client has no session to hold a choice in, and its key was issued against one
     * organisation deliberately.
     */
    public static final String PINNED_ORGANISATION_ATTRIBUTE = "clele.pinnedOrganisationId";

    private final AppUserRepository userRepository;
    private final OrganisationRepository organisationRepository;
    private final CurrentUserService currentUserService;

    /**
     * The organisation for this request: the one it is pinned to when a key chose it, otherwise the
     * session's choice when it is still selectable, otherwise the user's last one, otherwise their
     * first membership (alphabetically, so it is stable). The resolution is written back into the
     * session so subsequent calls agree.
     */
    public Organisation current() {
        AppUser me = currentUserService.current();

        Long pinnedOrgId = pinnedOrganisationId();
        if (pinnedOrgId != null) {
            // The filter that pinned it already checked the holder may work there, and there is no
            // session to fall back into — so a missing organisation is a broken credential, not a
            // reason to answer about a different catalogue.
            return organisationRepository.findById(pinnedOrgId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                            "The organisation this key was issued for no longer exists"));
        }

        Long sessionOrgId = sessionOrganisationId();
        if (sessionOrgId != null) {
            Optional<Organisation> chosen = selectableFor(me).stream()
                    .filter(o -> o.getId().equals(sessionOrgId))
                    .findFirst();
            if (chosen.isPresent()) {
                return chosen.get();
            }
        }

        Organisation resolved = defaultFor(me);
        storeInSession(resolved.getId());
        return resolved;
    }

    /** The authenticated user (convenience passthrough, so callers need one dependency less). */
    public AppUser currentUser() {
        return currentUserService.current();
    }

    /** Convenience for the many repository calls that only need the id. */
    public Long currentId() {
        return current().getId();
    }

    /**
     * Switch the current organisation, for this session and as the user's remembered default.
     * Rejects an organisation the user may not select (403).
     */
    @Transactional
    public Organisation switchTo(Long organisationId) {
        AppUser me = currentUserService.current();
        Organisation target = selectableFor(me).stream()
                .filter(o -> o.getId().equals(organisationId))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "You are not a member of that organisation"));

        storeInSession(target.getId());
        me.setLastOrganisation(target);
        userRepository.save(me);
        return target;
    }

    /** The organisations the current user may switch into. */
    public List<Organisation> selectable() {
        return selectableFor(currentUserService.current());
    }

    /** Whether the given organisation is one the current user may work in. */
    public boolean isSelectable(Long organisationId) {
        return selectable().stream().anyMatch(o -> o.getId().equals(organisationId));
    }

    /** Throw 403 unless the current user is a Global Administrator. */
    public void requireGlobalAdmin() {
        if (!isGlobalAdmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Global Administrator permission required");
        }
    }

    public boolean isGlobalAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return false;
        return auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(Permissions.GLOBAL_ADMIN::equals);
    }

    /**
     * A Global Administrator may select any organisation, including the template — that is how the
     * blueprint gets maintained. Everyone else is limited to their memberships, and never sees the
     * template.
     */
    private List<Organisation> selectableFor(AppUser user) {
        List<Organisation> all = isGlobalAdmin()
                ? organisationRepository.findAllByOrderByName()
                : user.getOrganisations().stream()
                        .filter(o -> !o.isTemplate())
                        .sorted(Comparator.comparing(Organisation::getName,
                                String.CASE_INSENSITIVE_ORDER))
                        .toList();
        return all;
    }

    private Organisation defaultFor(AppUser user) {
        List<Organisation> selectable = selectableFor(user);
        if (selectable.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "You are not a member of any organisation. Ask an administrator to add you to one.");
        }
        Organisation last = user.getLastOrganisation();
        if (last != null) {
            Optional<Organisation> stillSelectable = selectable.stream()
                    .filter(o -> o.getId().equals(last.getId()))
                    .findFirst();
            if (stillSelectable.isPresent()) {
                return stillSelectable.get();
            }
        }
        return selectable.get(0);
    }

    /** The organisation pinned on this request by an API-key filter, or null for a session request. */
    private Long pinnedOrganisationId() {
        if (RequestContextHolder.getRequestAttributes()
                instanceof ServletRequestAttributes attributes) {
            return (Long) attributes.getRequest().getAttribute(PINNED_ORGANISATION_ATTRIBUTE);
        }
        return null;
    }

    private Long sessionOrganisationId() {
        HttpSession session = session(false);
        return session == null ? null : (Long) session.getAttribute(SESSION_ATTRIBUTE);
    }

    private void storeInSession(Long organisationId) {
        HttpSession session = session(true);
        if (session != null) {
            session.setAttribute(SESSION_ATTRIBUTE, organisationId);
        }
    }

    /** The current request's session, or null when running outside a request (e.g. a background job). */
    private HttpSession session(boolean create) {
        if (RequestContextHolder.getRequestAttributes()
                instanceof ServletRequestAttributes attributes) {
            return attributes.getRequest().getSession(create);
        }
        return null;
    }
}
