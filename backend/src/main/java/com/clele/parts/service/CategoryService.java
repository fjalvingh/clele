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
    private final CurrentOrganisationService currentOrganisationService;

    public List<CategoryDTO> findAll() {
        return categoryRepository.findByOrganisationIdOrderByName(currentOrganisationService.currentId())
                .stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    public List<CategoryTreeDTO> getTree() {
        List<Category> roots = categoryRepository
                .findByOrganisationIdAndParentIsNull(currentOrganisationService.currentId());
        return roots.stream()
                .map(this::toTreeDTO)
                .collect(Collectors.toList());
    }

    public CategoryDTO findById(Long id) {
        return toDTO(requireCategory(id));
    }

    /** Categories outside the current organisation are reported as not found. */
    private Category requireCategory(Long id) {
        return categoryRepository.findByIdAndOrganisationId(id, currentOrganisationService.currentId())
                .orElseThrow(() -> new EntityNotFoundException("Category not found: " + id));
    }

    public List<CategoryDTO> findChildren(Long parentId) {
        requireCategory(parentId);
        return categoryRepository.findByParentId(parentId).stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    @Transactional
    public CategoryDTO create(CategoryRequest request) {
        Category category = new Category();
        category.setOrganisation(currentOrganisationService.current());
        category.setName(request.getName());
        category.setDescription(request.getDescription());
        if (request.getParentId() != null) {
            category.setParent(requireCategory(request.getParentId()));
        }
        return toDTO(categoryRepository.save(category));
    }

    @Transactional
    public CategoryDTO update(Long id, CategoryRequest request) {
        Category category = requireCategory(id);
        category.setName(request.getName());
        category.setDescription(request.getDescription());
        if (request.getParentId() != null) {
            if (request.getParentId().equals(id)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A category cannot be its own parent");
            }
            category.setParent(requireCategory(request.getParentId()));
        } else {
            category.setParent(null);
        }
        return toDTO(categoryRepository.save(category));
    }

    @Transactional
    public void delete(Long id) {
        Category category = requireCategory(id);
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
                .partCount(categoryRepository.countPartsByCategoryId(category.getId()))
                .children(childDTOs)
                .build();
    }
}
