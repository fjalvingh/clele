package com.clele.parts.service;

import com.clele.parts.dto.PartDTO;
import com.clele.parts.dto.PartRequest;
import com.clele.parts.dto.SpecsMode;
import com.clele.parts.model.AttachmentType;
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
import java.util.LinkedHashMap;
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
    private final CurrentOrganisationService currentOrganisationService;
    private final TagService tagService;
    private final SpecDefinitionService specDefinitionService;

    /**
     * Search the catalogue. Everything but {@code sort} is an optional filter, combined with AND:
     * the free-text {@code search} term, the category subtree, the personal-number flag, a
     * manufacturer substring, a location subtree, {@code sparseSpecs} (parts carrying fewer than
     * {@link PartRepository#SPARSE_SPEC_THRESHOLD} spec keys), and {@code tags} (a part must carry
     * <em>all</em> of the named tags — narrowing is what a tag filter is for).
     *
     * <p>Tags are matched here rather than in SQL: they are already loaded for the DTO mapping, and
     * an "all of N" match is awkward to express in a native query with a variable-length list.
     */
    public List<PartDTO> search(String search, Long categoryId, String sort,
                                Boolean personalNumber, String manufacturer, Long locationId,
                                Boolean sparseSpecs, List<String> tags) {
        String term = (search != null && !search.isBlank()) ? search.trim() : null;
        String maker = (manufacturer != null && !manufacturer.isBlank()) ? manufacturer.trim() : null;
        Comparator<PartDTO> comparator = comparatorFor(sort);
        List<Part> parts = partRepository.search(currentOrganisationService.currentId(), term,
                categoryId, personalNumber, maker, locationId, sparseSpecs);
        Set<String> wanted = (tags == null) ? Set.of() : tags.stream()
                .filter(t -> t != null && !t.isBlank())
                .map(t -> t.trim().toLowerCase())
                .collect(Collectors.toSet());
        if (!wanted.isEmpty()) {
            parts = parts.stream()
                    .filter(p -> p.getTags().stream()
                            .map(t -> t.getName().toLowerCase())
                            .collect(Collectors.toSet())
                            .containsAll(wanted))
                    .collect(Collectors.toList());
        }
        Map<Long, Long> stockByPart = stockByOrganisation(parts);
        Map<Long, Long> thumbnailByPart = thumbnailsFor(parts);
        return parts.stream()
                .map(p -> toDTOWithStock(p, stockByPart, thumbnailByPart))
                .sorted(comparator)
                .collect(Collectors.toList());
    }

    /**
     * First PHOTO attachment id per part, for the list thumbnail. One query for the whole result set;
     * parts without a photo are simply absent.
     */
    private Map<Long, Long> thumbnailsFor(List<Part> parts) {
        if (parts.isEmpty()) return Map.of();
        List<Long> ids = parts.stream().map(Part::getId).collect(Collectors.toList());
        Map<Long, Long> result = new HashMap<>();
        // Rows arrive ordered by display order, so the first one seen for a part is its first photo.
        partAttachmentRepository.findIdsByPartIdsAndType(ids, AttachmentType.PHOTO)
                .forEach(row -> result.putIfAbsent((Long) row[0], (Long) row[1]));
        return result;
    }

    /**
     * On-hand totals for the listed parts across the whole current organisation. Locations are
     * shared by every member, so this is an organisation figure, not a per-user one.
     */
    private Map<Long, Long> stockByOrganisation(List<Part> parts) {
        if (parts.isEmpty()) return Map.of();
        Long organisationId = currentOrganisationService.currentId();
        List<Long> ids = parts.stream().map(Part::getId).collect(Collectors.toList());
        Map<Long, Long> result = new HashMap<>();
        stockEntryRepository.sumQuantityByPartIdsAndOrganisationId(ids, organisationId)
                .forEach(row -> result.put((Long) row[0], (Long) row[1]));
        return result;
    }

    private PartDTO toDTOWithStock(Part part, Map<Long, Long> stockByPart,
                                   Map<Long, Long> thumbnailByPart) {
        PartDTO dto = toDTO(part);
        dto.setTotalQuantity(stockByPart.getOrDefault(part.getId(), 0L));
        dto.setThumbnailId(thumbnailByPart.get(part.getId()));
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
        List<Part> parts = partRepository.fuzzyByPartNumber(currentOrganisationService.currentId(), term);
        Map<Long, Long> stockByPart = stockByOrganisation(parts);
        Map<Long, Long> thumbnailByPart = thumbnailsFor(parts);
        return parts.stream()
                .map(p -> toDTOWithStock(p, stockByPart, thumbnailByPart))
                .collect(Collectors.toList());
    }

    public PartDTO findById(Long id) {
        return toDTO(requirePart(id));
    }

    /**
     * Load a part, refusing anything outside the current organisation. Reported as "not found"
     * rather than "forbidden": another organisation's catalogue does not exist as far as this one
     * is concerned.
     */
    Part requirePart(Long id) {
        return partRepository.findByIdAndOrganisationId(id, currentOrganisationService.currentId())
                .orElseThrow(() -> new EntityNotFoundException("Part not found: " + id));
    }

    @Transactional
    public PartDTO create(PartRequest request) {
        Long organisationId = currentOrganisationService.currentId();
        if (partRepository.existsByOrganisationIdAndPartNumber(organisationId, request.getPartNumber())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Part number already exists: " + request.getPartNumber());
        }
        Part part = new Part();
        // Set before buildPartFromRequest so category/tag resolution can scope to the organisation.
        part.setOrganisation(currentOrganisationService.current());
        part = buildPartFromRequest(part, request);
        part.setCreatedBy(currentUserService.current());
        return toDTO(partRepository.save(part));
    }

    @Transactional
    public PartDTO update(Long id, PartRequest request) {
        Part part = requirePart(id);
        if (partRepository.existsByOrganisationIdAndPartNumberAndIdNot(
                currentOrganisationService.currentId(), request.getPartNumber(), id)) {
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
        Part part = requirePart(id);

        part.setOctopartId(request.getOctopartId());

        if (request.getSpecs() != null) {
            java.util.Map<String, Object> merged = part.getSpecs() != null
                    ? new java.util.LinkedHashMap<>(part.getSpecs())
                    : new java.util.LinkedHashMap<>();
            merged.putAll(specDefinitionService.canonicalizeKeys(request.getSpecs()));
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
        requirePart(id);
        stockEntryRepository.deleteByPartId(id);
        partAttachmentRepository.deleteByPartId(id);
        partRepository.deleteById(id);
    }

    /**
     * Delete every part the given user created <em>in the current organisation</em>, along with its
     * stock entries, images and movement history. Used by an admin to undo one user's contributions
     * (e.g. a bad import) without affecting parts created by anyone else. Returns the number of
     * parts removed.
     */
    @Transactional
    public int deleteByUser(Long userId) {
        List<Long> partIds = partRepository.findIdsByCreatedByIdAndOrganisationId(
                userId, currentOrganisationService.currentId());
        if (partIds.isEmpty()) {
            return 0;
        }
        // stock_entry has no ON DELETE CASCADE (part_attachment and stock_movement do), so clear it
        // explicitly before removing the parts.
        stockEntryRepository.deleteByPartIdIn(partIds);
        return partRepository.deleteByIdIn(partIds);
    }

    public long countAll() {
        return partRepository.countByOrganisationId(currentOrganisationService.currentId());
    }

    /**
     * Parts in the current organisation carrying fewer than
     * {@link PartRepository#SPARSE_SPEC_THRESHOLD} spec keys — the dashboard's "missing specs"
     * figure. Clicking that tile lands on the Parts screen with the same filter applied, so the two
     * numbers must agree.
     */
    public long countSparseSpecs() {
        return partRepository.countSparseSpecs(currentOrganisationService.currentId());
    }

    private Part buildPartFromRequest(Part part, PartRequest request) {
        part.setPartNumber(request.getPartNumber());
        part.setDescription(request.getDescription());
        part.setDetails(request.getDetails());
        part.setManufacturer(request.getManufacturer());
        part.setPersonalNumber(request.isPersonalNumber());
        part.setDatasheetUrl(request.getDatasheetUrl());
        part.setSpecs(resolveSpecs(part, request));
        if (request.getCategoryId() != null) {
            Category category = categoryRepository
                    .findByIdAndOrganisationId(request.getCategoryId(), part.getOrganisation().getId())
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

    /**
     * The specs to store, combining the request's map with what the part already holds according to
     * {@code request.specsMode}.
     *
     * <p>The default is MERGE because a part can carry spec keys that no {@code spec_definition}
     * covers — the AI intake paths keep unrecognised keys deliberately, so that "rescan from parts"
     * can promote them to definitions later. A form that renders its fields from the definitions
     * therefore does not know about every key on the part, and saving it used to wipe the rest.
     * Under MERGE an omitted key means "leave it alone", so the only client that may send REPLACE is
     * one that rendered everything (today: the part edit modal, which shows undefined keys under
     * "Other" and needs REPLACE for its per-row remove button to work).
     *
     * <p>Since omitting a key under MERGE means "leave alone", clearing one needs an explicit
     * signal: a key present with a null or blank value is dropped.
     */
    private Map<String, Object> resolveSpecs(Part part, PartRequest request) {
        Map<String, Object> incoming = specDefinitionService.canonicalizeKeys(request.getSpecs());
        if (request.getSpecsMode() == SpecsMode.REPLACE) {
            return incoming;
        }
        Map<String, Object> merged = part.getSpecs() != null
                ? new LinkedHashMap<>(part.getSpecs())
                : new LinkedHashMap<>();
        if (incoming != null) {
            merged.putAll(incoming);
        }
        merged.values().removeIf(v -> v == null || String.valueOf(v).isBlank());
        return merged;
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
                .personalNumber(part.isPersonalNumber())
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
