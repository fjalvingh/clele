package com.clele.parts.service;

import com.clele.parts.dto.PartDTO;
import com.clele.parts.dto.PartRequest;
import com.clele.parts.model.Category;
import com.clele.parts.model.Part;
import com.clele.parts.repository.CategoryRepository;
import com.clele.parts.repository.PartImageRepository;
import com.clele.parts.repository.PartRepository;
import com.clele.parts.repository.StockEntryRepository;
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
public class PartService {

    private final PartRepository partRepository;
    private final CategoryRepository categoryRepository;
    private final StockEntryRepository stockEntryRepository;
    private final PartImageRepository partImageRepository;

    public List<PartDTO> search(String search, Long categoryId) {
        String term = (search != null && !search.isBlank()) ? search.toLowerCase() : null;
        return partRepository.findAllWithCategory().stream()
                .filter(p -> term == null
                        || p.getName().toLowerCase().contains(term)
                        || p.getPartNumber().toLowerCase().contains(term))
                .filter(p -> categoryId == null
                        || (p.getCategory() != null && p.getCategory().getId().equals(categoryId)))
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    public PartDTO findById(Long id) {
        Part part = partRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Part not found: " + id));
        return toDTO(part);
    }

    @Transactional
    public PartDTO create(PartRequest request) {
        if (partRepository.existsByPartNumber(request.getPartNumber())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Part number already exists: " + request.getPartNumber());
        }
        Part part = buildPartFromRequest(new Part(), request);
        return toDTO(partRepository.save(part));
    }

    @Transactional
    public PartDTO update(Long id, PartRequest request) {
        Part part = partRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Part not found: " + id));
        if (partRepository.existsByPartNumberAndIdNot(request.getPartNumber(), id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Part number already exists: " + request.getPartNumber());
        }
        return toDTO(partRepository.save(buildPartFromRequest(part, request)));
    }

    @Transactional
    public void delete(Long id) {
        if (!partRepository.existsById(id)) {
            throw new EntityNotFoundException("Part not found: " + id);
        }
        stockEntryRepository.deleteByPartId(id);
        partImageRepository.deleteByPartId(id);
        partRepository.deleteById(id);
    }

    public long countAll() {
        return partRepository.countAll();
    }

    private Part buildPartFromRequest(Part part, PartRequest request) {
        part.setPartNumber(request.getPartNumber());
        part.setName(request.getName());
        part.setDescription(request.getDescription());
        part.setManufacturer(request.getManufacturer());
        part.setDatasheetUrl(request.getDatasheetUrl());
        part.setSpecs(request.getSpecs());
        if (request.getCategoryId() != null) {
            Category category = categoryRepository.findById(request.getCategoryId())
                    .orElseThrow(() -> new EntityNotFoundException("Category not found: " + request.getCategoryId()));
            part.setCategory(category);
        } else {
            part.setCategory(null);
        }
        return part;
    }

    private String buildBreadcrumb(Category category) {
        if (category == null) return null;
        List<String> parts = new ArrayList<>();
        Category current = category;
        while (current != null) {
            parts.add(0, current.getName());
            current = current.getParent();
        }
        return String.join(" > ", parts);
    }

    public PartDTO toDTO(Part part) {
        return PartDTO.builder()
                .id(part.getId())
                .partNumber(part.getPartNumber())
                .name(part.getName())
                .description(part.getDescription())
                .manufacturer(part.getManufacturer())
                .datasheetUrl(part.getDatasheetUrl())
                .specs(part.getSpecs())
                .categoryId(part.getCategory() != null ? part.getCategory().getId() : null)
                .categoryName(part.getCategory() != null ? part.getCategory().getName() : null)
                .categoryBreadcrumb(buildBreadcrumb(part.getCategory()))
                .createdAt(part.getCreatedAt())
                .updatedAt(part.getUpdatedAt())
                .build();
    }
}
