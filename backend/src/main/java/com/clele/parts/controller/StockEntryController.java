package com.clele.parts.controller;

import com.clele.parts.dto.StockEntryDTO;
import com.clele.parts.dto.StockEntryRequest;
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
@RequestMapping("/api/stock")
@RequiredArgsConstructor
@Tag(name = "Stock", description = "Stock entry management endpoints")
public class StockEntryController {

    private final StockEntryService stockEntryService;

    @GetMapping
    @Operation(summary = "List all stock entries")
    public List<StockEntryDTO> listAll() {
        return stockEntryService.findAll();
    }

    @GetMapping("/low")
    @Operation(summary = "List stock entries below minimum quantity")
    public List<StockEntryDTO> getLowStock() {
        return stockEntryService.findLowStock();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get stock entry by ID")
    public StockEntryDTO getById(@PathVariable Long id) {
        return stockEntryService.findById(id);
    }

    @PostMapping
    @Operation(summary = "Add a stock entry")
    public ResponseEntity<StockEntryDTO> create(@Valid @RequestBody StockEntryRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(stockEntryService.create(request));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update a stock entry")
    public StockEntryDTO update(@PathVariable Long id, @Valid @RequestBody StockEntryRequest request) {
        return stockEntryService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a stock entry")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        stockEntryService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
