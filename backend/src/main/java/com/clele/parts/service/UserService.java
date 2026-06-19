package com.clele.parts.service;

import com.clele.parts.dto.UserDTO;
import com.clele.parts.dto.UserRequest;
import com.clele.parts.model.AppUser;
import com.clele.parts.model.Location;
import com.clele.parts.repository.AppUserRepository;
import com.clele.parts.repository.LocationRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserService {

    private final AppUserRepository userRepository;
    private final LocationRepository locationRepository;
    private final PasswordEncoder passwordEncoder;

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
        if (request.getDefaultLocationName() == null || request.getDefaultLocationName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A default location name is required");
        }
        String email = normalizeEmail(request.getEmail());
        if (userRepository.existsByEmail(email)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already exists: " + email);
        }
        AppUser user = AppUser.builder()
                .email(email)
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .fullName(request.getFullName())
                .phone(request.getPhone())
                .permissions(sanitizePermissions(request.getPermissions()))
                .build();
        user = userRepository.save(user);
        // Every user must own a default location; create it now and mark it default.
        Location defaultLocation = locationRepository.save(Location.builder()
                .name(request.getDefaultLocationName().trim())
                .owner(user)
                .build());
        user.setDefaultLocation(defaultLocation);
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
        // Only change the password when a new, non-blank one is supplied.
        if (request.getPassword() != null && !request.getPassword().isBlank()) {
            user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        }
        // Re-point the default location (must be one the user owns).
        if (request.getDefaultLocationId() != null) {
            Location loc = locationRepository.findById(request.getDefaultLocationId())
                    .orElseThrow(() -> new EntityNotFoundException(
                            "Location not found: " + request.getDefaultLocationId()));
            if (loc.getOwner() == null || !loc.getOwner().getId().equals(user.getId())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Default location must be one the user owns");
            }
            user.setDefaultLocation(loc);
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

    public UserDTO toDTO(AppUser user) {
        Location defaultLocation = user.getDefaultLocation();
        return UserDTO.builder()
                .id(user.getId())
                .email(user.getEmail())
                .fullName(user.getFullName())
                .phone(user.getPhone())
                .permissions(new HashSet<>(user.getPermissions()))
                .defaultLocationId(defaultLocation != null ? defaultLocation.getId() : null)
                .defaultLocationName(defaultLocation != null ? defaultLocation.getName() : null)
                .hasOctopartCredentials(
                        user.getOctopartClientId() != null && !user.getOctopartClientId().isBlank()
                        && user.getOctopartClientSecret() != null && !user.getOctopartClientSecret().isBlank())
                .build();
    }
}
