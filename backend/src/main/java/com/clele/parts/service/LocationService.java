package com.clele.parts.service;

import com.clele.parts.dto.LocationDTO;
import com.clele.parts.dto.LocationRequest;
import com.clele.parts.model.Location;
import com.clele.parts.repository.LocationRepository;
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

    public List<LocationDTO> findAll() {
        return locationRepository.findAll().stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    public LocationDTO findById(Long id) {
        return toDTO(locationRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Location not found: " + id)));
    }

    @Transactional
    public LocationDTO create(LocationRequest request) {
        if (locationRepository.existsByName(request.getName())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Location name already exists: " + request.getName());
        }
        Location location = Location.builder()
                .name(request.getName())
                .description(request.getDescription())
                .build();
        return toDTO(locationRepository.save(location));
    }

    @Transactional
    public LocationDTO update(Long id, LocationRequest request) {
        Location location = locationRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Location not found: " + id));
        if (locationRepository.existsByNameAndIdNot(request.getName(), id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Location name already exists: " + request.getName());
        }
        location.setName(request.getName());
        location.setDescription(request.getDescription());
        return toDTO(locationRepository.save(location));
    }

    @Transactional
    public void delete(Long id) {
        Location location = locationRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Location not found: " + id));
        locationRepository.delete(location);
    }

    public long countAll() {
        return locationRepository.count();
    }

    private LocationDTO toDTO(Location location) {
        return LocationDTO.builder()
                .id(location.getId())
                .name(location.getName())
                .description(location.getDescription())
                .build();
    }
}
