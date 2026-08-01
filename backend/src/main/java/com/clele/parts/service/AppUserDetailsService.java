package com.clele.parts.service;

import com.clele.parts.model.AppUser;
import com.clele.parts.repository.AppUserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Loads users for Spring Security by email, granting their <em>global</em> permissions only.
 *
 * <p>Per-organisation permissions cannot be resolved here: authentication happens before an
 * organisation is in force, and the answer changes when the user switches. {@code PermissionService}
 * re-issues the authentication with the full set once the organisation is known (at login) and
 * again on every switch.
 */
@Service
@RequiredArgsConstructor
public class AppUserDetailsService implements UserDetailsService {

    private final AppUserRepository userRepository;

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        AppUser user = userRepository.findByEmail(email == null ? null : email.trim().toLowerCase())
                .orElseThrow(() -> new UsernameNotFoundException("No user with email: " + email));
        List<SimpleGrantedAuthority> authorities = user.getPermissions().stream()
                .map(SimpleGrantedAuthority::new)
                .toList();
        return new User(user.getEmail(), user.getPasswordHash(), authorities);
    }
}
