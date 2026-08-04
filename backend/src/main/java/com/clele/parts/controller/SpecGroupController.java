package com.clele.parts.controller;

import com.clele.parts.dto.SpecDefinitionDTO;
import com.clele.parts.dto.SpecGroupDTO;
import com.clele.parts.dto.SpecGroupRequest;
import com.clele.parts.service.SpecDefinitionService;
import com.clele.parts.service.SpecGroupService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/spec-groups")
@RequiredArgsConstructor
@Tag(name = "Spec Groups", description = "Groups of related specification fields")
public class SpecGroupController {

    private final SpecGroupService groupService;
    private final SpecDefinitionService specService;

    @GetMapping
    @Operation(summary = "List all spec groups with their field counts")
    public List<SpecGroupDTO> listAll() {
        return groupService.findAll();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get one spec group")
    public SpecGroupDTO get(@PathVariable Long id) {
        return groupService.findById(id);
    }

    @GetMapping("/{id}/spec-definitions")
    @Operation(summary = "List the spec fields in a group")
    public List<SpecDefinitionDTO> specs(@PathVariable Long id) {
        return specService.findByGroup(id);
    }

    @PostMapping
    @Operation(summary = "Create a spec group")
    public ResponseEntity<SpecGroupDTO> create(@Valid @RequestBody SpecGroupRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(groupService.create(request));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update a spec group")
    public SpecGroupDTO update(@PathVariable Long id, @Valid @RequestBody SpecGroupRequest request) {
        return groupService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete an empty spec group")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        groupService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
