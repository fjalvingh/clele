package com.clele.parts.service;

import com.clele.parts.model.AppUser;
import com.clele.parts.model.Permissions;
import com.clele.parts.repository.AppUserRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves the currently authenticated {@link AppUser} from the Spring Security context.
 * The authentication name is the user's email (see {@code AppUserDetailsService}).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CurrentUserService {

    private final AppUserRepository userRepository;

    /** The authenticated user, or throw if there is no authenticated session. */
    public AppUser current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getName() == null) {
            throw new EntityNotFoundException("No authenticated user");
        }
        String email = auth.getName().trim().toLowerCase();
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new EntityNotFoundException("User not found: " + email));
    }

    /** Whether the current authentication carries the USERS_EDIT authority (admin). */
    public boolean isAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return false;
        return auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(Permissions.USERS_EDIT::equals);
    }
}
