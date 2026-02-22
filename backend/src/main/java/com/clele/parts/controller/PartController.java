package com.clele.parts.controller;

import com.clele.parts.dto.PartDTO;
import com.clele.parts.dto.PartRequest;
import com.clele.parts.dto.StockEntryDTO;
import com.clele.parts.service.PartService;
import com.clele.parts.service.StockEntryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/parts")
@RequiredArgsConstructor
@Tag(name = "Parts", description = "Part catalog management endpoints")
public class PartController {

    private final PartService partService;
    private final StockEntryService stockEntryService;

    @GetMapping
    @Operation(summary = "List / search parts")
    public List<PartDTO> search(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Long categoryId) {
        return partService.search(search, categoryId);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get part by ID")
    public PartDTO getById(@PathVariable Long id) {
        return partService.findById(id);
    }

    @GetMapping("/{id}/stock")
    @Operation(summary = "Get all stock entries for a part")
    public List<StockEntryDTO> getStockForPart(@PathVariable Long id) {
        return stockEntryService.findByPartId(id);
    }

    @PostMapping
    @Operation(summary = "Create a new part")
    public ResponseEntity<PartDTO> create(@Valid @RequestBody PartRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(partService.create(request));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update a part")
    public PartDTO update(@PathVariable Long id, @Valid @RequestBody PartRequest request) {
        return partService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a part")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        partService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
