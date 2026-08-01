package com.clele.parts.service;

import com.clele.parts.dto.OrganisationDTO;
import com.clele.parts.dto.UserDTO;
import com.clele.parts.dto.UserRequest;
import com.clele.parts.model.AppUser;
import com.clele.parts.model.Location;
import com.clele.parts.model.Organisation;
import com.clele.parts.repository.AppUserRepository;
import com.clele.parts.repository.OrganisationRepository;
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

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserService {

    private final AppUserRepository userRepository;
    private final OrganisationRepository organisationRepository;
    private final PasswordEncoder passwordEncoder;
    private final ChangesService changesService;

    public List<UserDTO> findAll() {
        return userRepository.findAll().stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    public UserDTO findById(Long id) {
        return toDTO(getOrThrow(id));
    }

    @Transactional
    public UserDTO create(UserRequest request) {
        if (request.getPassword() == null || request.getPassword().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password is required");
        }
        String email = normalizeEmail(request.getEmail());
        if (userRepository.existsByEmail(email)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already exists: " + email);
        }
        Set<Organisation> organisations = resolveOrganisations(request.getOrganisationIds());
        AppUser user = AppUser.builder()
                .email(email)
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .fullName(request.getFullName())
                .phone(request.getPhone())
                .permissions(sanitizePermissions(request.getPermissions()))
                .organisations(organisations)
                .build();
        // Start the user off in the first of their organisations; they can switch at any time.
        user.setLastOrganisation(organisations.iterator().next());
        user.setLastReadChanges(changesService.getLatestDate());
        return toDTO(userRepository.save(user));
    }

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
        user.setPermissions(sanitizePermissions(request.getPermissions()));
        Set<Organisation> organisations = resolveOrganisations(request.getOrganisationIds());
        user.setOrganisations(organisations);
        // A user removed from the organisation they were last in falls back to one they still have.
        if (user.getLastOrganisation() == null
                || organisations.stream().noneMatch(o -> o.getId().equals(user.getLastOrganisation().getId()))) {
            user.setLastOrganisation(organisations.iterator().next());
        }
        // Only change the password when a new, non-blank one is supplied.
        if (request.getPassword() != null && !request.getPassword().isBlank()) {
            user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        }
        return toDTO(userRepository.save(user));
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
     * Every user must belong to at least one organisation — without one there is nothing for them
     * to see or do, and no sensible default to fall back on.
     */
    private Set<Organisation> resolveOrganisations(Set<Long> organisationIds) {
        if (organisationIds == null || organisationIds.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A user must belong to at least one organisation");
        }
        Set<Organisation> organisations = new LinkedHashSet<>();
        for (Long id : organisationIds) {
            organisations.add(organisationRepository.findById(id)
                    .orElseThrow(() -> new EntityNotFoundException("Organisation not found: " + id)));
        }
        return organisations;
    }

    private String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase();
    }

    private Set<String> sanitizePermissions(Set<String> permissions) {
        if (permissions == null) return new HashSet<>();
        return permissions.stream()
                .filter(p -> p != null && !p.isBlank())
                .map(String::trim)
                .collect(Collectors.toCollection(HashSet::new));
    }

    /**
     * The DTO for {@code /auth/me}: the plain user plus the session's organisation context, which
     * the sidebar switcher needs. Kept separate from {@link #toDTO} because the current
     * organisation is a property of the caller's session, not of the user row — including it in the
     * Users list would repeat the admin's own context on every row.
     */
    public UserDTO toCurrentUserDTO(AppUser user, Organisation current,
                                    List<OrganisationDTO> selectable) {
        UserDTO dto = toDTO(user);
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

    public UserDTO toDTO(AppUser user) {
        Location lastLocation = user.getLastLocation();
        return UserDTO.builder()
                .id(user.getId())
                .email(user.getEmail())
                .fullName(user.getFullName())
                .phone(user.getPhone())
                .permissions(new HashSet<>(user.getPermissions()))
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
