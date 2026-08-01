package com.clele.parts.service;

import com.clele.parts.dto.AdminUserDTO;
import com.clele.parts.dto.UserMembershipDTO;
import com.clele.parts.dto.UserRequest;
import com.clele.parts.model.AppUser;
import com.clele.parts.model.Organisation;
import com.clele.parts.model.Permissions;
import com.clele.parts.repository.AppUserRepository;
import com.clele.parts.repository.OrganisationRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Installation-wide user administration — the "All Users" screen. Global Administrator only
 * (enforced at {@code AdminUserController}).
 *
 * <p>Separate from {@link UserService} because the two answer different questions and must not be
 * confused. {@code UserService} is organisation-scoped: it sees only members of the organisation in
 * force and only their permissions there, which is exactly an Organisation Admin's reach. This
 * service crosses every boundary — all accounts, all memberships, all per-organisation permissions
 * — so nothing here may be reachable without {@code GLOBAL_ADMIN}.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminUserService {

    private final AppUserRepository userRepository;
    private final OrganisationRepository organisationRepository;
    private final PasswordEncoder passwordEncoder;
    private final CurrentUserService currentUserService;

    /** Every account, with all of its memberships. */
    public List<AdminUserDTO> findAll() {
        return userRepository.findAllByOrderByEmail().stream().map(this::toDTO).toList();
    }

    public AdminUserDTO findById(Long id) {
        return toDTO(getOrThrow(id));
    }

    /**
     * Update account details: name, email, phone, optionally the password, and the global
     * permissions. Per-organisation permissions are set through
     * {@link #setPermissions(Long, Long, Set)} instead — they belong to a membership, not the
     * account.
     */
    @Transactional
    public AdminUserDTO update(Long id, UserRequest request) {
        AppUser user = getOrThrow(id);
        String email = normalizeEmail(request.getEmail());
        if (userRepository.existsByEmailAndIdNot(email, id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already exists: " + email);
        }
        user.setEmail(email);
        user.setFullName(request.getFullName());
        user.setPhone(request.getPhone());
        if (request.getGlobalPermissions() != null) {
            Set<String> global = sanitize(request.getGlobalPermissions(), Permissions.GLOBAL::contains);
            refuseSelfDemotion(user, global);
            user.setPermissions(global);
        }
        // A blank password means "leave it alone" — the field is empty on the edit form.
        if (request.getPassword() != null && !request.getPassword().isBlank()) {
            user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        }
        return toDTO(userRepository.save(user));
    }

    /** Add the user to an organisation, with no permissions in it to start with. */
    @Transactional
    public AdminUserDTO addToOrganisation(Long userId, Long organisationId) {
        AppUser user = getOrThrow(userId);
        Organisation organisation = organisationRepository.findById(organisationId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Organisation not found: " + organisationId));
        if (isMember(user, organisationId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "That user is already a member of " + organisation.getName());
        }
        user.getOrganisations().add(organisation);
        return toDTO(userRepository.save(user));
    }

    /**
     * Remove the user from an organisation. The permissions they held there go with the membership
     * — leaving them behind would silently restore access if they were ever re-added.
     */
    @Transactional
    public AdminUserDTO removeFromOrganisation(Long userId, Long organisationId) {
        AppUser user = getOrThrow(userId);
        requireMembership(user, organisationId);
        if (user.getOrganisations().size() <= 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This is the user's only organisation. Delete the account instead.");
        }
        user.getOrganisations().removeIf(o -> o.getId().equals(organisationId));
        user.setPermissionsIn(organisationId, Set.of());
        if (user.getLastOrganisation() != null
                && user.getLastOrganisation().getId().equals(organisationId)) {
            user.setLastOrganisation(user.getOrganisations().iterator().next());
        }
        return toDTO(userRepository.save(user));
    }

    /** Replace the user's permissions within one organisation they belong to. */
    @Transactional
    public AdminUserDTO setPermissions(Long userId, Long organisationId, Set<String> permissions) {
        AppUser user = getOrThrow(userId);
        requireMembership(user, organisationId);
        user.setPermissionsIn(organisationId,
                sanitize(permissions, Permissions.PER_ORGANISATION::contains));
        return toDTO(userRepository.save(user));
    }

    /**
     * Refuse to strip your own Global Administrator permission. This screen is the only place it
     * can be granted, so giving it up here locks the installation out of its own administration
     * whenever nobody else holds it — and the mistake cannot be undone from the UI.
     */
    private void refuseSelfDemotion(AppUser user, Set<String> newGlobalPermissions) {
        boolean self = user.getId().equals(currentUserService.current().getId());
        if (self && user.isGlobalAdmin()
                && !newGlobalPermissions.contains(Permissions.GLOBAL_ADMIN)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "You cannot remove your own Global Administrator permission. "
                    + "Ask another Global Administrator to do it.");
        }
    }

    private boolean isMember(AppUser user, Long organisationId) {
        return user.getOrganisations().stream().anyMatch(o -> o.getId().equals(organisationId));
    }

    private void requireMembership(AppUser user, Long organisationId) {
        if (!isMember(user, organisationId)) {
            throw new EntityNotFoundException(
                    "That user is not a member of organisation " + organisationId);
        }
    }

    private AppUser getOrThrow(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("User not found: " + id));
    }

    private String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase();
    }

    private Set<String> sanitize(Set<String> permissions, java.util.function.Predicate<String> allowed) {
        if (permissions == null) return new HashSet<>();
        return permissions.stream()
                .filter(p -> p != null && !p.isBlank())
                .map(String::trim)
                .filter(allowed)
                .collect(Collectors.toCollection(HashSet::new));
    }

    private AdminUserDTO toDTO(AppUser user) {
        boolean globalAdmin = user.isGlobalAdmin();
        List<UserMembershipDTO> memberships = user.getOrganisations().stream()
                .sorted(Comparator.comparing(Organisation::getName, String.CASE_INSENSITIVE_ORDER))
                .map(o -> UserMembershipDTO.builder()
                        .organisationId(o.getId())
                        .organisationName(o.getName())
                        .template(o.isTemplate())
                        // permissionsIn() already returns everything for a Global Administrator,
                        // so this shows what applies rather than what happens to be stored.
                        .permissions(user.permissionsIn(o.getId()))
                        .implied(globalAdmin)
                        .build())
                .toList();
        return AdminUserDTO.builder()
                .id(user.getId())
                .email(user.getEmail())
                .fullName(user.getFullName())
                .phone(user.getPhone())
                .globalPermissions(new HashSet<>(user.getPermissions()))
                .memberships(memberships)
                .build();
    }
}
