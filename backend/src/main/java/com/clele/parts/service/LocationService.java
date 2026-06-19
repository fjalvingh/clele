package com.clele.parts.service;

import com.clele.parts.dto.LocationDTO;
import com.clele.parts.dto.LocationRequest;
import com.clele.parts.model.AppUser;
import com.clele.parts.model.Location;
import com.clele.parts.repository.AppUserRepository;
import com.clele.parts.repository.LocationRepository;
import com.clele.parts.repository.StockEntryRepository;
import com.clele.parts.repository.StockMovementRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LocationService {

    private final LocationRepository locationRepository;
    private final AppUserRepository userRepository;
    private final StockEntryRepository stockEntryRepository;
    private final StockMovementRepository stockMovementRepository;
    private final CurrentUserService currentUserService;

    public List<LocationDTO> findAll() {
        return locationRepository.findAll().stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    /** Locations owned by the currently authenticated user (for stock-add pickers). */
    public List<LocationDTO> findMine() {
        AppUser me = currentUserService.current();
        return locationRepository.findByOwnerIdOrderByName(me.getId()).stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    public LocationDTO findById(Long id) {
        return toDTO(getOrThrow(id));
    }

    @Transactional
    public LocationDTO create(LocationRequest request) {
        AppUser owner = currentUserService.current();
        if (locationRepository.existsByOwnerIdAndName(owner.getId(), request.getName())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "You already have a location named: " + request.getName());
        }
        Location location = Location.builder()
                .name(request.getName())
                .description(request.getDescription())
                .owner(owner)
                .build();
        return toDTO(locationRepository.save(location));
    }

    @Transactional
    public LocationDTO update(Long id, LocationRequest request) {
        Location location = getOrThrow(id);
        requireManagePermission(location);
        Long ownerId = location.getOwner().getId();
        if (locationRepository.existsByOwnerIdAndNameAndIdNot(ownerId, request.getName(), id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "That owner already has a location named: " + request.getName());
        }
        location.setName(request.getName());
        location.setDescription(request.getDescription());
        return toDTO(locationRepository.save(location));
    }

    @Transactional
    public void delete(Long id) {
        Location location = getOrThrow(id);
        requireManagePermission(location);
        if (userRepository.existsByDefaultLocationId(id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This location is a user's default location and cannot be deleted");
        }
        if (stockEntryRepository.existsByLocationId(id) || stockMovementRepository.existsByLocationId(id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This location has stock or stock history and cannot be deleted");
        }
        locationRepository.delete(location);
    }

    public long countAll() {
        return locationRepository.count();
    }

    private Location getOrThrow(Long id) {
        return locationRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Location not found: " + id));
    }

    /** A location may be managed by its owner or by an admin (USERS_EDIT). */
    private void requireManagePermission(Location location) {
        AppUser me = currentUserService.current();
        boolean owns = location.getOwner() != null && location.getOwner().getId().equals(me.getId());
        if (!owns && !currentUserService.isAdmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "You can only manage your own locations");
        }
    }

    private LocationDTO toDTO(Location location) {
        AppUser owner = location.getOwner();
        return LocationDTO.builder()
                .id(location.getId())
                .name(location.getName())
                .description(location.getDescription())
                .ownerId(owner != null ? owner.getId() : null)
                .ownerName(owner != null ? (owner.getFullName() != null ? owner.getFullName() : owner.getEmail()) : null)
                .build();
    }
}
