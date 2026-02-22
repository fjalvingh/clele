package com.clele.parts.service;

import com.clele.parts.dto.SpecDefinitionDTO;
import com.clele.parts.dto.SpecDefinitionRequest;
import com.clele.parts.model.Category;
import com.clele.parts.model.SpecDefinition;
import com.clele.parts.repository.CategoryRepository;
import com.clele.parts.repository.SpecDefinitionRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SpecDefinitionService {

    private final SpecDefinitionRepository specRepo;
    private final CategoryRepository categoryRepository;
    private final ObjectMapper objectMapper;

    public List<SpecDefinitionDTO> findAll() {
        return specRepo.findAllByOrderByDisplayOrderAscNameAsc().stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    @Transactional
    public SpecDefinitionDTO create(SpecDefinitionRequest request) {
        SpecDefinition spec = new SpecDefinition();
        applyRequest(spec, request);
        return toDTO(specRepo.save(spec));
    }

    @Transactional
    public SpecDefinitionDTO update(Long id, SpecDefinitionRequest request) {
        SpecDefinition spec = specRepo.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("SpecDefinition not found: " + id));
        applyRequest(spec, request);
        return toDTO(specRepo.save(spec));
    }

    @Transactional
    public void delete(Long id) {
        SpecDefinition spec = specRepo.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("SpecDefinition not found: " + id));
        specRepo.delete(spec);
    }

    /**
     * Returns the inherited (union) spec definitions for a category, walking up the parent chain.
     * If categoryId is null, returns all definitions.
     */
    public List<SpecDefinitionDTO> getInheritedSpecs(Long categoryId) {
        if (categoryId == null) {
            return findAll();
        }

        Category category = categoryRepository.findById(categoryId)
                .orElseThrow(() -> new EntityNotFoundException("Category not found: " + categoryId));

        // Walk up the ancestor chain, collecting specs (deduplicating by ID, preserving order)
        Map<Long, SpecDefinition> collected = new LinkedHashMap<>();
        Category current = category;
        while (current != null) {
            for (SpecDefinition spec : current.getSpecs()) {
                collected.putIfAbsent(spec.getId(), spec);
            }
            current = current.getParent();
        }

        // Sort by display_order ASC, name ASC
        return collected.values().stream()
                .sorted((a, b) -> {
                    int cmp = Integer.compare(a.getDisplayOrder(), b.getDisplayOrder());
                    return cmp != 0 ? cmp : a.getName().compareTo(b.getName());
                })
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    private void applyRequest(SpecDefinition spec, SpecDefinitionRequest request) {
        spec.setName(request.getName());
        spec.setDataType(request.getDataType() != null ? request.getDataType() : "TEXT");
        spec.setUnit(request.getUnit());
        spec.setDisplayOrder(request.getDisplayOrder());

        if (request.getOptions() != null && !request.getOptions().isEmpty()) {
            try {
                spec.setOptions(objectMapper.writeValueAsString(request.getOptions()));
            } catch (JsonProcessingException e) {
                spec.setOptions(null);
            }
        } else {
            spec.setOptions(null);
        }
    }

    SpecDefinitionDTO toDTO(SpecDefinition spec) {
        List<String> options = parseOptions(spec.getOptions());
        return SpecDefinitionDTO.builder()
                .id(spec.getId())
                .name(spec.getName())
                .dataType(spec.getDataType())
                .unit(spec.getUnit())
                .options(options.isEmpty() ? null : options)
                .displayOrder(spec.getDisplayOrder())
                .build();
    }

    private List<String> parseOptions(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (JsonProcessingException e) {
            return new ArrayList<>();
        }
    }
}
