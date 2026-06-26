package com.clele.parts.controller;

import com.clele.parts.dto.StockAdjustRequest;
import com.clele.parts.dto.StockEntryDTO;
import com.clele.parts.dto.StockEntryRequest;
import com.clele.parts.dto.StockMoveRequest;
import com.clele.parts.service.StockEntryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

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

    @PostMapping("/add")
    @Operation(summary = "Add a quantity of stock at a location (creates the entry if needed)")
    public StockEntryDTO add(@Valid @RequestBody StockAdjustRequest request) {
        return stockEntryService.addStock(request);
    }

    @PostMapping("/take")
    @Operation(summary = "Take a quantity of stock from a location")
    public StockEntryDTO take(@Valid @RequestBody StockAdjustRequest request) {
        return stockEntryService.takeStock(request);
    }

    @PostMapping("/move")
    @Operation(summary = "Move a quantity of stock from one location to another (destination may belong to any user)")
    public ResponseEntity<Void> move(@Valid @RequestBody StockMoveRequest request) {
        stockEntryService.move(request);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a stock entry")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        stockEntryService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/reconcile")
    @PreAuthorize("hasAuthority('PARTS_EDIT')")
    @Operation(summary = "Realign every stock entry's on-hand quantity to its ledger")
    public Map<String, Integer> reconcile() {
        return Map.of("corrected", stockEntryService.reconcile());
    }
}
