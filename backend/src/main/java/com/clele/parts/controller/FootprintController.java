package com.clele.parts.controller;

import com.clele.parts.dto.FootprintDTO;
import com.clele.parts.dto.FootprintMergeRequest;
import com.clele.parts.dto.FootprintRequest;
import com.clele.parts.model.Permissions;
import com.clele.parts.service.FootprintService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/footprints")
@RequiredArgsConstructor
@Tag(name = "Footprints", description = "The organisation's list of package types and their aliases")
public class FootprintController {

    private final FootprintService footprintService;

    @GetMapping
    @Operation(summary = "List the footprints with their aliases and how many parts use each")
    public List<FootprintDTO> list() {
        return footprintService.findAll();
    }

    @PostMapping
    @PreAuthorize("hasAuthority('" + Permissions.PARTS_EDIT + "')")
    @Operation(summary = "Add a footprint")
    public ResponseEntity<FootprintDTO> create(@Valid @RequestBody FootprintRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(footprintService.create(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.PARTS_EDIT + "')")
    @Operation(summary = "Change a footprint; a rename moves its parts along")
    public FootprintDTO update(@PathVariable Long id, @Valid @RequestBody FootprintRequest request) {
        return footprintService.update(id, request);
    }

    @PostMapping("/{id}/merge")
    @PreAuthorize("hasAuthority('" + Permissions.PARTS_EDIT + "')")
    @Operation(summary = "Merge a footprint into another: its parts move, its names become aliases")
    public FootprintDTO merge(@PathVariable Long id, @Valid @RequestBody FootprintMergeRequest request) {
        return footprintService.merge(id, request.getTargetId());
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.PARTS_EDIT + "')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a footprint no part uses")
    public void delete(@PathVariable Long id) {
        footprintService.delete(id);
    }
}
