package com.clele.parts.controller;

import com.clele.parts.dto.LocationDTO;
import com.clele.parts.dto.LocationRequest;
import com.clele.parts.service.LocationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/locations")
@RequiredArgsConstructor
@Tag(name = "Locations", description = "Storage location management endpoints")
public class LocationController {

    private final LocationService locationService;

    @GetMapping
    @Operation(summary = "List all locations")
    public List<LocationDTO> listAll() {
        return locationService.findAll();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get location by ID")
    public LocationDTO getById(@PathVariable Long id) {
        return locationService.findById(id);
    }

    @PostMapping
    @Operation(summary = "Create a new location")
    public ResponseEntity<LocationDTO> create(@Valid @RequestBody LocationRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(locationService.create(request));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update a location")
    public LocationDTO update(@PathVariable Long id, @Valid @RequestBody LocationRequest request) {
        return locationService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a location")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        locationService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
