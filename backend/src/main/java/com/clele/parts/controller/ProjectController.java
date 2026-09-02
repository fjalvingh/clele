package com.clele.parts.controller;

import com.clele.parts.dto.*;
import com.clele.parts.model.Permissions;
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

/**
 * Projects and their part lists.
 *
 * <p>The part list lives at {@code /parts}; {@code /bom} next door is the <i>imported</i> BOM and
 * belongs to {@link ProjectBomController}. Keeping the two paths distinct is the point — they are
 * different lists and were confusing while both were called a BOM.
 */
@RestController
@RequestMapping("/api/projects")
@RequiredArgsConstructor
@Tag(name = "Projects", description = "Projects and their part lists")
@PreAuthorize("hasAuthority('" + Permissions.PARTS_EDIT + "')")
public class ProjectController {

    private final ProjectService projectService;

    @GetMapping
    @Operation(summary = "List current user's projects")
    public List<ProjectDTO> listProjects() {
        return projectService.findAll();
    }

    @PostMapping
    @Operation(summary = "Create a project (starts ACTIVE)")
    public ResponseEntity<ProjectDTO> createProject(@Valid @RequestBody ProjectRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(projectService.create(request));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a project with its part list")
    public ProjectDTO getProject(@PathVariable Long id) {
        return projectService.findById(id);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update name/description/instance count (ACTIVE only; re-allocates)")
    public ProjectDTO updateProject(@PathVariable Long id, @Valid @RequestBody ProjectRequest request) {
        return projectService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a cancelled project and everything belonging to it")
    public ResponseEntity<Void> deleteProject(@PathVariable Long id) {
        projectService.delete(id);
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------
    // Part list
    // ------------------------------------------------------------------

    @PostMapping("/{id}/parts")
    @Operation(summary = "Add a part to the project's part list, allocating it from stock")
    public ResponseEntity<ProjectPartDTO> addPart(
            @PathVariable Long id,
            @Valid @RequestBody ProjectPartRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(projectService.addPart(id, request));
    }

    @PutMapping("/{id}/parts/{projectPartId}")
    @Operation(summary = "Change a part list line's quantity, allocating or returning the difference")
    public ProjectPartDTO updatePart(
            @PathVariable Long id,
            @PathVariable Long projectPartId,
            @Valid @RequestBody ProjectPartRequest request) {
        return projectService.updatePart(id, projectPartId, request);
    }

    @DeleteMapping("/{id}/parts/{projectPartId}")
    @Operation(summary = "Remove a part list line, returning everything it holds to stock")
    public ResponseEntity<Void> removePart(@PathVariable Long id, @PathVariable Long projectPartId) {
        projectService.removePart(id, projectPartId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/parts/{projectPartId}/return")
    @Operation(summary = "Return some of a part list line's allocation to the locations it came from")
    public ProjectPartDTO returnPart(
            @PathVariable Long id,
            @PathVariable Long projectPartId,
            @Valid @RequestBody ReturnPartRequest request) {
        return projectService.returnPart(id, projectPartId, request);
    }

    // ------------------------------------------------------------------
    // Phase transitions
    // ------------------------------------------------------------------

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Cancel the project, returning every allocation to stock")
    public ProjectDTO cancel(@PathVariable Long id) {
        return projectService.cancel(id);
    }

    @PostMapping("/{id}/activate")
    @Operation(summary = "Reactivate a cancelled project, allocating its part list from stock again")
    public ProjectDTO activate(@PathVariable Long id) {
        return projectService.activate(id);
    }
}
