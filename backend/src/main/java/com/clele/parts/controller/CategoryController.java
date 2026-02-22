package com.clele.parts.controller;

import com.clele.parts.dto.*;
import com.clele.parts.service.CategoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/categories")
@RequiredArgsConstructor
@Tag(name = "Categories", description = "Category management endpoints")
public class CategoryController {

    private final CategoryService categoryService;

    @GetMapping
    @Operation(summary = "List all categories (flat)")
    public List<CategoryDTO> listAll() {
        return categoryService.findAll();
    }

    @GetMapping("/tree")
    @Operation(summary = "Get full category hierarchy as nested tree")
    public List<CategoryTreeDTO> getTree() {
        return categoryService.getTree();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get category by ID (with breadcrumb)")
    public CategoryDTO getById(@PathVariable Long id) {
        return categoryService.findById(id);
    }

    @GetMapping("/{id}/children")
    @Operation(summary = "Get immediate children of a category")
    public List<CategoryDTO> getChildren(@PathVariable Long id) {
        return categoryService.findChildren(id);
    }

    @PostMapping
    @Operation(summary = "Create a new category")
    public ResponseEntity<CategoryDTO> create(@Valid @RequestBody CategoryRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(categoryService.create(request));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update a category")
    public CategoryDTO update(@PathVariable Long id, @Valid @RequestBody CategoryRequest request) {
        return categoryService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a category")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        categoryService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
