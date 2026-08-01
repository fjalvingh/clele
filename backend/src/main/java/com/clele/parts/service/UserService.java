package com.clele.parts.service;

import com.clele.parts.dto.OrganisationDTO;
import com.clele.parts.dto.UserDTO;
import com.clele.parts.dto.UserRequest;
import com.clele.parts.model.AppUser;
import com.clele.parts.model.Location;
import com.clele.parts.model.Organisation;
import com.clele.parts.model.Permissions;
import com.clele.parts.repository.AppUserRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * User management, scoped to the organisation currently in force.
 *
 * <p>An account is global (unique email) but a <em>membership</em> and the permissions attached to
 * it are not: this service only ever reads and writes the current organisation's side of a user.
 * An Organisation Admin can therefore never see or change what a user may do elsewhere.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserService {

    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final ChangesService changesService;
    private final CurrentOrganisationService currentOrganisationService;

    /** The members of the current organisation. */
    public List<UserDTO> findAll() {
        Organisation organisation = currentOrganisationService.current();
        return userRepository.findByOrganisationsIdOrderByEmail(organisation.getId()).stream()
                .map(user -> toDTO(user, organisation))
                .collect(Collectors.toList());
    }

    public UserDTO findById(Long id) {
        Organisation organisation = currentOrganisationService.current();
        return toDTO(requireMember(id, organisation), organisation);
    }

    /**
     * Create a new account and make it a member of the current organisation. Restricted to a Global
     * Administrator at the controller: an account is an installation-wide object.
     */
    @Transactional
    public UserDTO create(UserRequest request) {
        if (request.getPassword() == null || request.getPassword().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password is required");
        }
        String email = normalizeEmail(request.getEmail());
        if (userRepository.existsByEmail(email)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already exists: " + email);
        }
        Organisation organisation = currentOrganisationService.current();
        AppUser user = AppUser.builder()
                .email(email)
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .fullName(request.getFullName())
                .phone(request.getPhone())
                .permissions(sanitizeGlobalPermissions(request.getGlobalPermissions()))
                .organisations(new HashSet<>(Set.of(organisation)))
                .build();
        user.setPermissionsIn(organisation.getId(),
                sanitizeOrganisationPermissions(request.getPermissions()));
        user.setLastOrganisation(organisation);
        user.setLastReadChanges(changesService.getLatestDate());
        return toDTO(userRepository.save(user), organisation);
    }

    /** Add an existing account to the current organisation, with no permissions to start with. */
    @Transactional
    public UserDTO addMember(String rawEmail) {
        String email = normalizeEmail(rawEmail);
        AppUser user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No user account with email: " + email));
        Organisation organisation = currentOrganisationService.current();
        if (user.getOrganisations().stream().anyMatch(o -> o.getId().equals(organisation.getId()))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "That user is already a member of this organisation");
        }
        user.getOrganisations().add(organisation);
        return toDTO(userRepository.save(user), organisation);
    }

    /**
     * Remove a user from the current organisation. The account itself and its memberships elsewhere
     * are untouched; the permissions they held here go with the membership.
     */
    @Transactional
    public void removeMember(Long id) {
        Organisation organisation = currentOrganisationService.current();
        AppUser user = requireMember(id, organisation);
        if (user.getId().equals(currentOrganisationService.currentUser().getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "You cannot remove yourself from the organisation");
        }
        if (user.getOrganisations().size() <= 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This is the user's only organisation. Delete the account instead.");
        }
        user.getOrganisations().removeIf(o -> o.getId().equals(organisation.getId()));
        user.setPermissionsIn(organisation.getId(), Set.of());
        if (user.getLastOrganisation() != null
                && user.getLastOrganisation().getId().equals(organisation.getId())) {
            user.setLastOrganisation(user.getOrganisations().iterator().next());
        }
        userRepository.save(user);
    }

    /**
     * Set a member's permissions <em>within the current organisation</em>. Deliberately cannot
     * touch permissions in any other organisation, nor global ones: an Organisation Admin's reach
     * stops at the organisation they administer.
     */
    @Transactional
    public UserDTO updatePermissionsInCurrentOrganisation(Long id, Set<String> permissions) {
        Organisation organisation = currentOrganisationService.current();
        AppUser user = requireMember(id, organisation);
        user.setPermissionsIn(organisation.getId(), sanitizeOrganisationPermissions(permissions));
        return toDTO(userRepository.save(user), organisation);
    }

    /** Update account details. Global Administrator only — an account is installation-wide. */
    @Transactional
    public UserDTO update(Long id, UserRequest request) {
        AppUser user = getOrThrow(id);
        String email = normalizeEmail(request.getEmail());
        if (userRepository.existsByEmailAndIdNot(email, id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already exists: " + email);
        }
        user.setEmail(email);
        user.setFullName(request.getFullName());
        user.setPhone(request.getPhone());
        if (request.getGlobalPermissions() != null) {
            user.setPermissions(sanitizeGlobalPermissions(request.getGlobalPermissions()));
        }
        if (request.getPermissions() != null) {
            user.setPermissionsIn(currentOrganisationService.currentId(),
                    sanitizeOrganisationPermissions(request.getPermissions()));
        }
        // Only change the password when a new, non-blank one is supplied.
        if (request.getPassword() != null && !request.getPassword().isBlank()) {
            user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        }
        return toDTO(userRepository.save(user), currentOrganisationService.current());
    }

    @Transactional
    public void delete(Long id) {
        userRepository.delete(getOrThrow(id));
    }

    public long countAll() {
        return userRepository.count();
    }

    private AppUser getOrThrow(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("User not found: " + id));
    }

    /**
     * Load a user, refusing anyone who is not a member of {@code organisation} — reported as "not
     * found", consistent with how the rest of the app treats another organisation's data.
     */
    private AppUser requireMember(Long id, Organisation organisation) {
        AppUser user = getOrThrow(id);
        boolean member = user.getOrganisations().stream()
                .anyMatch(o -> o.getId().equals(organisation.getId()));
        if (!member) {
            throw new EntityNotFoundException("User not found: " + id);
        }
        return user;
    }

    private String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase();
    }

    /** Keep only recognised global permissions — a per-organisation key here would be meaningless. */
    private Set<String> sanitizeGlobalPermissions(Set<String> permissions) {
        return sanitize(permissions, Permissions.GLOBAL::contains);
    }

    /** Keep only recognised per-organisation permissions; GLOBAL_ADMIN cannot be granted this way. */
    private Set<String> sanitizeOrganisationPermissions(Set<String> permissions) {
        return sanitize(permissions, Permissions.PER_ORGANISATION::contains);
    }

    private Set<String> sanitize(Set<String> permissions, java.util.function.Predicate<String> allowed) {
        if (permissions == null) return new HashSet<>();
        return permissions.stream()
                .filter(p -> p != null && !p.isBlank())
                .map(String::trim)
                .filter(allowed)
                .collect(Collectors.toCollection(HashSet::new));
    }

    /**
     * The DTO for {@code /auth/me}: the plain user plus the session's organisation context, which
     * the sidebar switcher needs. Kept separate from {@link #toDTO} because the current
     * organisation is a property of the caller's session, not of the user row.
     */
    public UserDTO toCurrentUserDTO(AppUser user, Organisation current,
                                    List<OrganisationDTO> selectable) {
        UserDTO dto = toDTO(user, current);
        // The last-used location is remembered across organisations, so drop it when it belongs to
        // a different one — it would otherwise pre-select a location the pickers cannot show.
        Location lastLocation = user.getLastLocation();
        if (lastLocation == null || lastLocation.getOrganisation() == null
                || !lastLocation.getOrganisation().getId().equals(current.getId())) {
            dto.setLastLocationId(null);
            dto.setLastLocationName(null);
        }
        dto.setCurrentOrganisationId(current.getId());
        dto.setCurrentOrganisationName(current.getName());
        dto.setSelectableOrganisations(selectable);
        return dto;
    }

    /**
     * @param organisation the organisation whose permissions {@code permissions} should report —
     *                     always the one currently in force, since that is the only one the caller
     *                     is administering.
     */
    public UserDTO toDTO(AppUser user, Organisation organisation) {
        Location lastLocation = user.getLastLocation();
        return UserDTO.builder()
                .id(user.getId())
                .email(user.getEmail())
                .fullName(user.getFullName())
                .phone(user.getPhone())
                .permissions(organisation == null
                        ? new HashSet<>()
                        : user.permissionsIn(organisation.getId()))
                .globalPermissions(new HashSet<>(user.getPermissions()))
                .lastLocationId(lastLocation != null ? lastLocation.getId() : null)
                .lastLocationName(lastLocation != null ? lastLocation.breadcrumb() : null)
                .organisationIds(user.getOrganisations().stream()
                        .map(Organisation::getId)
                        .collect(Collectors.toCollection(LinkedHashSet::new)))
                .hasOctopartCredentials(
                        user.getOctopartClientId() != null && !user.getOctopartClientId().isBlank()
                        && user.getOctopartClientSecret() != null && !user.getOctopartClientSecret().isBlank())
                .lastReadChanges(user.getLastReadChanges())
                .printMethod(user.getPrintMethod() == null ? null : user.getPrintMethod().name())
                .preferredDaemonId(user.getPreferredDaemon() == null ? null : user.getPreferredDaemon().getId())
                .printBarcodeLabel(user.isPrintBarcodeLabel())
                .build();
    }
}
