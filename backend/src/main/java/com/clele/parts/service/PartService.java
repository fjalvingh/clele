package com.clele.parts.service;

import com.clele.parts.dto.PartDTO;
import com.clele.parts.dto.PartRequest;
import com.clele.parts.model.Category;
import com.clele.parts.model.Part;
import com.clele.parts.model.Tag;
import com.clele.parts.repository.CategoryRepository;
import com.clele.parts.repository.PartAttachmentRepository;
import com.clele.parts.repository.PartRepository;
import com.clele.parts.repository.StockEntryRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PartService {

    private final PartRepository partRepository;
    private final CategoryRepository categoryRepository;
    private final StockEntryRepository stockEntryRepository;
    private final PartAttachmentRepository partAttachmentRepository;
    private final CurrentUserService currentUserService;
    private final TagService tagService;

    public List<PartDTO> search(String search, Long categoryId, String sort) {
        String term = (search != null && !search.isBlank()) ? search.trim() : null;
        Comparator<PartDTO> comparator = comparatorFor(sort);
        List<Part> parts = partRepository.search(term, categoryId);
        Map<Long, Long> stockByPart = stockByOwner(parts);
        return parts.stream()
                .map(p -> toDTOWithStock(p, stockByPart))
                .sorted(comparator)
                .collect(Collectors.toList());
    }

    private Map<Long, Long> stockByOwner(List<Part> parts) {
        if (parts.isEmpty()) return Map.of();
        Long ownerId = currentUserService.current().getId();
        List<Long> ids = parts.stream().map(Part::getId).collect(Collectors.toList());
        Map<Long, Long> result = new HashMap<>();
        stockEntryRepository.sumQuantityByPartIdsAndOwnerId(ids, ownerId)
                .forEach(row -> result.put((Long) row[0], (Long) row[1]));
        return result;
    }

    private PartDTO toDTOWithStock(Part part, Map<Long, Long> stockByPart) {
        PartDTO dto = toDTO(part);
        dto.setTotalQuantity(stockByPart.getOrDefault(part.getId(), 0L));
        return dto;
    }

    /** Build the result comparator. Supported sorts: "manufacturer"; anything else → part number. */
    private Comparator<PartDTO> comparatorFor(String sort) {
        Comparator<String> nullsLastCi =
                Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER);
        Comparator<PartDTO> byPartNumber =
                Comparator.comparing(PartDTO::getPartNumber, nullsLastCi);
        if ("manufacturer".equalsIgnoreCase(sort)) {
            return Comparator.comparing(PartDTO::getManufacturer, nullsLastCi)
                    .thenComparing(byPartNumber);
        }
        return byPartNumber;
    }

    /**
     * Fuzzy-match existing parts by part number (used by Quick Add to surface an already-catalogued
     * part before searching the Internet). Blank terms return no matches.
     */
    public List<PartDTO> fuzzyByPartNumber(String q) {
        String term = (q != null) ? q.trim() : "";
        if (term.isEmpty()) {
            return List.of();
        }
        List<Part> parts = partRepository.fuzzyByPartNumber(term);
        Map<Long, Long> stockByPart = stockByOwner(parts);
        return parts.stream()
                .map(p -> toDTOWithStock(p, stockByPart))
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
        part.setCreatedBy(currentUserService.current());
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

    /**
     * Enrich a part from a chosen OctoPart result. Always sets the {@code octopartId} link and
     * overlays the supplied specs onto the part's existing specs. Each non-null column field (name,
     * description, manufacturer, mpn, footprint, datasheet) is a change the user explicitly
     * confirmed; null fields are left unchanged. Does not touch images.
     */
    @Transactional
    public PartDTO applyOctopart(Long id, com.clele.parts.dto.OctopartApplyRequest request) {
        Part part = partRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Part not found: " + id));

        part.setOctopartId(request.getOctopartId());

        if (request.getSpecs() != null) {
            java.util.Map<String, Object> merged = part.getSpecs() != null
                    ? new java.util.LinkedHashMap<>(part.getSpecs())
                    : new java.util.LinkedHashMap<>();
            merged.putAll(request.getSpecs());
            part.setSpecs(merged);
        }

        if (request.getDescription() != null) part.setDescription(request.getDescription());
        if (request.getManufacturer() != null) part.setManufacturer(request.getManufacturer());
        if (request.getMpn() != null) part.setMpn(request.getMpn());
        if (request.getFootprint() != null) part.setFootprint(request.getFootprint());
        if (request.getDatasheetUrl() != null) part.setDatasheetUrl(request.getDatasheetUrl());

        return toDTO(partRepository.save(part));
    }

    @Transactional
    public void delete(Long id) {
        if (!partRepository.existsById(id)) {
            throw new EntityNotFoundException("Part not found: " + id);
        }
        stockEntryRepository.deleteByPartId(id);
        partAttachmentRepository.deleteByPartId(id);
        partRepository.deleteById(id);
    }

    /**
     * Delete every part created by the given user, along with its stock entries, images and
     * movement history. Used by an admin to undo one user's contributions (e.g. a bad import)
     * without affecting parts created by anyone else. Returns the number of parts removed.
     */
    @Transactional
    public int deleteByUser(Long userId) {
        List<Long> partIds = partRepository.findIdsByCreatedById(userId);
        if (partIds.isEmpty()) {
            return 0;
        }
        // stock_entry has no ON DELETE CASCADE (part_attachment and stock_movement do), so clear it
        // explicitly before removing the parts.
        stockEntryRepository.deleteByPartIdIn(partIds);
        return partRepository.deleteByCreatedById(userId);
    }

    public long countAll() {
        return partRepository.countAll();
    }

    private Part buildPartFromRequest(Part part, PartRequest request) {
        part.setPartNumber(request.getPartNumber());
        part.setDescription(request.getDescription());
        part.setDetails(request.getDetails());
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
        if (request.getTags() != null) {
            Set<Tag> resolved = tagService.resolveOrCreate(request.getTags());
            part.getTags().clear();
            part.getTags().addAll(resolved);
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
                .description(part.getDescription())
                .details(part.getDetails())
                .manufacturer(part.getManufacturer())
                .footprint(part.getFootprint())
                .mpn(part.getMpn())
                .octopartId(part.getOctopartId())
                .datasheetUrl(part.getDatasheetUrl())
                .specs(part.getSpecs())
                .categoryId(part.getCategory() != null ? part.getCategory().getId() : null)
                .categoryName(part.getCategory() != null ? part.getCategory().getName() : null)
                .categoryBreadcrumb(buildBreadcrumb(part.getCategory()))
                .createdById(part.getCreatedBy() != null ? part.getCreatedBy().getId() : null)
                .createdByName(part.getCreatedBy() != null
                        ? (part.getCreatedBy().getFullName() != null
                                ? part.getCreatedBy().getFullName()
                                : part.getCreatedBy().getEmail())
                        : null)
                .createdAt(part.getCreatedAt())
                .updatedAt(part.getUpdatedAt())
                .tags(part.getTags().stream()
                        .map(Tag::getName)
                        .sorted(String.CASE_INSENSITIVE_ORDER)
                        .collect(Collectors.toList()))
                .build();
    }
}
