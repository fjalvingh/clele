package com.clele.parts.controller;

import com.clele.parts.dto.StockThresholdDTO;
import com.clele.parts.dto.StockThresholdRequest;
import com.clele.parts.service.StockThresholdService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/stock-thresholds")
@RequiredArgsConstructor
@Tag(name = "Stock Thresholds", description = "Minimum stock levels per part and root location")
public class StockThresholdController {

    private final StockThresholdService stockThresholdService;

    @GetMapping("/low")
    @Operation(summary = "List thresholds where total on-hand is below minimum")
    public List<StockThresholdDTO> getLowStock() {
        return stockThresholdService.findLowStock();
    }

    @GetMapping
    @Operation(summary = "List all thresholds, optionally filtered by part")
    public List<StockThresholdDTO> listAll(@RequestParam(required = false) Long partId) {
        return stockThresholdService.findAll(partId);
    }

    @PostMapping
    @Operation(summary = "Create or update a stock threshold (location must be a root location)")
    public ResponseEntity<StockThresholdDTO> upsert(@Valid @RequestBody StockThresholdRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(stockThresholdService.upsert(request));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a stock threshold")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        stockThresholdService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
