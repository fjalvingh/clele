package com.clele.parts.controller;

import com.clele.parts.dto.SupplierDTO;
import com.clele.parts.dto.SupplierRequest;
import com.clele.parts.model.Permissions;
import com.clele.parts.service.order.SupplierService;
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
@RequestMapping("/api/suppliers")
@RequiredArgsConstructor
@Tag(name = "Suppliers", description = "The distributors an organisation orders from")
@PreAuthorize("hasAuthority('" + Permissions.PARTS_EDIT + "')")
public class SupplierController {

    private final SupplierService supplierService;

    @GetMapping
    @Operation(summary = "List the organisation's suppliers")
    public List<SupplierDTO> list() {
        return supplierService.findAll();
    }

    @PostMapping
    @Operation(summary = "Add a supplier")
    public ResponseEntity<SupplierDTO> create(@Valid @RequestBody SupplierRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(supplierService.create(request));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Rename a supplier")
    public SupplierDTO rename(@PathVariable Long id, @Valid @RequestBody SupplierRequest request) {
        return supplierService.rename(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a supplier nothing was ordered from")
    public void delete(@PathVariable Long id) {
        supplierService.delete(id);
    }
}
