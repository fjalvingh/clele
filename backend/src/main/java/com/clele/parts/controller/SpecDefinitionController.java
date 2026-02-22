package com.clele.parts.controller;

import com.clele.parts.dto.SpecDefinitionDTO;
import com.clele.parts.dto.SpecDefinitionRequest;
import com.clele.parts.service.SpecDefinitionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/spec-definitions")
@RequiredArgsConstructor
@Tag(name = "Spec Definitions", description = "Spec field definition management")
public class SpecDefinitionController {

    private final SpecDefinitionService specService;

    @GetMapping
    @Operation(summary = "List all spec definitions (sorted)")
    public List<SpecDefinitionDTO> listAll() {
        return specService.findAll();
    }

    @PostMapping
    @Operation(summary = "Create a new spec definition")
    public ResponseEntity<SpecDefinitionDTO> create(@Valid @RequestBody SpecDefinitionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(specService.create(request));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update a spec definition")
    public SpecDefinitionDTO update(@PathVariable Long id, @Valid @RequestBody SpecDefinitionRequest request) {
        return specService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a spec definition")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        specService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/for-category/{catId}")
    @Operation(summary = "Get inherited spec definitions for a category")
    public List<SpecDefinitionDTO> forCategory(@PathVariable Long catId) {
        return specService.getInheritedSpecs(catId);
    }
}
