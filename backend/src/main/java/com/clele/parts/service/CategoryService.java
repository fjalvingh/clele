package com.clele.parts.service;

import com.clele.parts.dto.*;
import com.clele.parts.model.Category;
import com.clele.parts.repository.CategoryRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CategoryService {

    private final CategoryRepository categoryRepository;

    public List<CategoryDTO> findAll() {
        return categoryRepository.findAll().stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    public List<CategoryTreeDTO> getTree() {
        List<Category> roots = categoryRepository.findByParentIsNull();
        return roots.stream()
                .map(this::toTreeDTO)
                .collect(Collectors.toList());
    }

    public CategoryDTO findById(Long id) {
        Category c = categoryRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Category not found: " + id));
        return toDTO(c);
    }

    public List<CategoryDTO> findChildren(Long parentId) {
        categoryRepository.findById(parentId)
                .orElseThrow(() -> new EntityNotFoundException("Category not found: " + parentId));
        return categoryRepository.findByParentId(parentId).stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    @Transactional
    public CategoryDTO create(CategoryRequest request) {
        Category category = new Category();
        category.setName(request.getName());
        category.setDescription(request.getDescription());
        if (request.getParentId() != null) {
            Category parent = categoryRepository.findById(request.getParentId())
                    .orElseThrow(() -> new EntityNotFoundException("Parent category not found: " + request.getParentId()));
            category.setParent(parent);
        }
        return toDTO(categoryRepository.save(category));
    }

    @Transactional
    public CategoryDTO update(Long id, CategoryRequest request) {
        Category category = categoryRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Category not found: " + id));
        category.setName(request.getName());
        category.setDescription(request.getDescription());
        if (request.getParentId() != null) {
            if (request.getParentId().equals(id)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A category cannot be its own parent");
            }
            Category parent = categoryRepository.findById(request.getParentId())
                    .orElseThrow(() -> new EntityNotFoundException("Parent category not found: " + request.getParentId()));
            category.setParent(parent);
        } else {
            category.setParent(null);
        }
        return toDTO(categoryRepository.save(category));
    }

    @Transactional
    public void delete(Long id) {
        Category category = categoryRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Category not found: " + id));
        if (categoryRepository.existsByParentId(id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Cannot delete category with children. Delete or reassign children first.");
        }
        if (categoryRepository.countPartsByCategoryId(id) > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Cannot delete category that has parts assigned to it.");
        }
        categoryRepository.delete(category);
    }

    private String buildBreadcrumb(Category category) {
        List<String> parts = new ArrayList<>();
        Category current = category;
        while (current != null) {
            parts.add(0, current.getName());
            current = current.getParent();
        }
        return String.join(" > ", parts);
    }

    private CategoryDTO toDTO(Category category) {
        return CategoryDTO.builder()
                .id(category.getId())
                .name(category.getName())
                .description(category.getDescription())
                .parentId(category.getParent() != null ? category.getParent().getId() : null)
                .parentName(category.getParent() != null ? category.getParent().getName() : null)
                .breadcrumb(buildBreadcrumb(category))
                .build();
    }

    private CategoryTreeDTO toTreeDTO(Category category) {
        List<CategoryTreeDTO> childDTOs = category.getChildren().stream()
                .map(this::toTreeDTO)
                .collect(Collectors.toList());
        return CategoryTreeDTO.builder()
                .id(category.getId())
                .name(category.getName())
                .description(category.getDescription())
                .parentId(category.getParent() != null ? category.getParent().getId() : null)
                .children(childDTOs)
                .build();
    }
}
