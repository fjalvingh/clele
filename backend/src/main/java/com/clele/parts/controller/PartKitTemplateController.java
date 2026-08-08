package com.clele.parts.controller;

import com.clele.parts.dto.PartKitGenerateRequest;
import com.clele.parts.dto.PartKitGenerateResultDTO;
import com.clele.parts.dto.PartKitTemplateDTO;
import com.clele.parts.dto.PartKitTemplateRequest;
import com.clele.parts.service.PartKitTemplateService;
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
@RequestMapping("/api/part-kit-templates")
@RequiredArgsConstructor
@Tag(name = "Part kit templates", description = "Templates for packs of parts that differ in one value")
@PreAuthorize("hasAuthority('PARTS_EDIT')")
public class PartKitTemplateController {

    private final PartKitTemplateService service;

    @GetMapping
    @Operation(summary = "List the current organisation's kit templates")
    public List<PartKitTemplateDTO> list() {
        return service.findAll();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get one kit template with its values")
    public PartKitTemplateDTO get(@PathVariable Long id) {
        return service.findById(id);
    }

    @PostMapping
    @Operation(summary = "Create a kit template")
    public ResponseEntity<PartKitTemplateDTO> create(@Valid @RequestBody PartKitTemplateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update a kit template, including its whole value list")
    public PartKitTemplateDTO update(@PathVariable Long id,
                                     @Valid @RequestBody PartKitTemplateRequest request) {
        return service.update(id, request);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a kit template (the parts it generated are untouched)")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/generate")
    @Operation(summary = "Create or find a part per value and add stock to each")
    public PartKitGenerateResultDTO generate(@PathVariable Long id,
                                             @Valid @RequestBody PartKitGenerateRequest request) {
        return service.generate(id, request);
    }
}
