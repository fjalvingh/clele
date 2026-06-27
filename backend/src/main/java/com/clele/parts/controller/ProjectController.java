package com.clele.parts.controller;

import com.clele.parts.dto.*;
import com.clele.parts.service.ProjectService;
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
@RequestMapping("/api/projects")
@RequiredArgsConstructor
@Tag(name = "Projects", description = "Project / build management")
@PreAuthorize("hasAuthority('PARTS_EDIT')")
public class ProjectController {

    private final ProjectService projectService;

    @GetMapping
    @Operation(summary = "List current user's projects")
    public List<ProjectDTO> listProjects() {
        return projectService.findAll();
    }

    @PostMapping
    @Operation(summary = "Create a project (starts in PLANNING)")
    public ResponseEntity<ProjectDTO> createProject(@Valid @RequestBody ProjectRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(projectService.create(request));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get project with BOM and stock")
    public ProjectDTO getProject(@PathVariable Long id) {
        return projectService.findById(id);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update project name/description/instanceCount (PLANNING only)")
    public ProjectDTO updateProject(@PathVariable Long id, @Valid @RequestBody ProjectRequest request) {
        return projectService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete project (PLANNING only)")
    public ResponseEntity<Void> deleteProject(@PathVariable Long id) {
        projectService.delete(id);
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------
    // BOM
    // ------------------------------------------------------------------

    @PostMapping("/{id}/bom")
    @Operation(summary = "Add a part to the BOM")
    public ResponseEntity<ProjectBomEntryDTO> addBomEntry(
            @PathVariable Long id,
            @Valid @RequestBody ProjectBomRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(projectService.addBomEntry(id, request));
    }

    @PutMapping("/{id}/bom/{bomId}")
    @Operation(summary = "Update a BOM entry")
    public ProjectBomEntryDTO updateBomEntry(
            @PathVariable Long id,
            @PathVariable Long bomId,
            @Valid @RequestBody ProjectBomRequest request) {
        return projectService.updateBomEntry(id, bomId, request);
    }

    @DeleteMapping("/{id}/bom/{bomId}")
    @Operation(summary = "Remove a part from the BOM")
    public ResponseEntity<Void> removeBomEntry(@PathVariable Long id, @PathVariable Long bomId) {
        projectService.removeBomEntry(id, bomId);
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------
    // State transitions
    // ------------------------------------------------------------------

    @PostMapping("/{id}/start-build")
    @Operation(summary = "Transition project from PLANNING to BUILDING")
    public ProjectDTO startBuild(@PathVariable Long id) {
        return projectService.startBuild(id);
    }

    @PostMapping("/{id}/pull-stock")
    @Operation(summary = "Pull parts from a stock location into the project (BUILDING only)")
    public ResponseEntity<ProjectStockEntryDTO> pullStock(
            @PathVariable Long id,
            @Valid @RequestBody PullStockRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(projectService.pullStock(id, request));
    }

    @PostMapping("/{id}/complete")
    @Operation(summary = "Mark project as COMPLETED")
    public ProjectDTO complete(@PathVariable Long id) {
        return projectService.complete(id);
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Cancel project, optionally returning selected stock to source locations")
    public ProjectDTO cancel(@PathVariable Long id, @RequestBody CancelRequest request) {
        return projectService.cancel(id, request);
    }
}
