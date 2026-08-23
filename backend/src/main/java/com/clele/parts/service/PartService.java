package com.clele.parts.service;

import com.clele.parts.dto.PartCreateRequest;
import com.clele.parts.dto.PartDTO;
import com.clele.parts.dto.PartRequest;
import com.clele.parts.dto.RecentPartDTO;
import com.clele.parts.dto.RecentPartsPageDTO;
import com.clele.parts.dto.SpecsMode;
import com.clele.parts.model.AttachmentType;
import com.clele.parts.model.Category;
import com.clele.parts.model.Location;
import com.clele.parts.model.MovementType;
import com.clele.parts.model.Part;
import com.clele.parts.model.StockEntry;
import com.clele.parts.model.SpecDefinition;
import com.clele.parts.model.Tag;
import com.clele.parts.repository.CategoryRepository;
import com.clele.parts.repository.LocationRepository;
import com.clele.parts.repository.PartAttachmentLinkRepository;
import com.clele.parts.repository.PartRepository;
import com.clele.parts.repository.StockEntryRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
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
    private final LocationRepository locationRepository;
    private final StockEntryRepository stockEntryRepository;
    private final StockMovementService stockMovementService;
    private final PartAttachmentLinkRepository partAttachmentLinkRepository;
    private final PartAttachmentService partAttachmentService;
    private final CurrentUserService currentUserService;
    private final CurrentOrganisationService currentOrganisationService;
    private final TagService tagService;
    private final SpecDefinitionService specDefinitionService;
    private final PartSpecValueService partSpecValueService;
    private final com.clele.parts.repository.PartSpecValueRepository partSpecValueRepository;
    private final com.clele.parts.repository.SpecDefinitionRepository specDefinitionRepository;

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
                                Boolean sparseSpecs, List<String> tags, List<String> specs) {
        String term = (search != null && !search.isBlank()) ? search.trim() : null;
        String maker = (manufacturer != null && !manufacturer.isBlank()) ? manufacturer.trim() : null;
        Comparator<PartDTO> comparator = comparatorFor(sort);
        List<Part> parts = partRepository.search(currentOrganisationService.currentId(), term,
                categoryId, personalNumber, maker, locationId, sparseSpecs);
        parts = applySpecCriteria(parts, specs);
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
        Map<Long, Map<String, Object>> specsByPart = specsFor(parts);
        return parts.stream()
                .map(p -> toDTOWithStock(p, stockByPart, thumbnailByPart, specsByPart))
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
        partAttachmentLinkRepository.findIdsByPartIdsAndType(ids, AttachmentType.PHOTO)
                .forEach(row -> result.putIfAbsent((Long) row[0], (Long) row[1]));
        return result;
    }

    /**
     * Narrow the result set by parametric spec criteria — the query a parts database exists for
     * ("Vds ≥ 60 V", "resistance = 4.7 kΩ"), and the reason the typed rows exist.
     *
     * <p>Each criterion is {@code jsonName:op:value} and runs as its own indexed query; the results
     * are intersected, so criteria AND together the way the other filters do. This mirrors how tags
     * are handled — the selective work happens in SQL, the combining in Java — rather than building
     * the whole search as dynamic SQL for a filter most searches do not use.
     *
     * <p><b>The value is parsed against the spec's own unit family</b>, so a user may type
     * {@code 4k7}, {@code 100nF} or {@code 3.3} and mean the same thing the catalogue stores. A
     * value that will not parse as a number falls back to a text match, which is what makes
     * {@code dielectric:eq:X7R} work through the same mechanism.
     *
     * <p>An unknown spec name or an unusable criterion matches <b>nothing</b> rather than being
     * ignored: silently dropping a filter shows the user a longer list and lets them believe it was
     * filtered.
     */
    private List<Part> applySpecCriteria(List<Part> parts, List<String> specs) {
        if (specs == null || specs.isEmpty() || parts.isEmpty()) return parts;
        Long orgId = currentOrganisationService.currentId();

        for (String raw : specs) {
            if (raw == null || raw.isBlank()) continue;
            String[] bits = raw.split(":", 3);
            if (bits.length < 2) return List.of();

            String jsonName = bits[0].trim();
            String op = bits[1].trim().toLowerCase();
            String value = bits.length > 2 ? bits[2].trim() : "";

            SpecDefinition def = specDefinitionRepository
                    .findByOrganisationIdAndJsonName(orgId, jsonName).orElse(null);
            if (def == null) return List.of();

            Set<Long> matching = new HashSet<>(matchingPartIds(orgId, def, op, value));
            parts = parts.stream().filter(p -> matching.contains(p.getId())).collect(Collectors.toList());
            if (parts.isEmpty()) return parts;
        }
        return parts;
    }

    /** The part ids one criterion admits. */
    private List<Long> matchingPartIds(Long orgId, SpecDefinition def, String op, String value) {
        if ("any".equals(op)) {
            return partSpecValueRepository.partIdsWithSpec(orgId, def.getJsonName());
        }
        if (value.isEmpty()) return List.of();

        if ("contains".equals(op)) {
            return partSpecValueRepository.partIdsMatchingText(orgId, def.getJsonName(), op, value);
        }

        // The value is written the way people write it — "4k7", "100nF", "3.3" — so it is parsed
        // through the spec's own family, exactly as an incoming spec value would be.
        BigDecimal num = def.family()
                .flatMap(f -> MetricUnitParser.parseToBase(value, f))
                .map(BigDecimal::new)
                .orElseGet(() -> {
                    try {
                        return new BigDecimal(MetricUnitParser.normalizeSpaces(value));
                    } catch (NumberFormatException e) {
                        return null;
                    }
                });

        if (num != null) {
            return partSpecValueRepository.partIdsMatchingNumeric(orgId, def.getJsonName(), op, num);
        }
        // Not a number: only equality is meaningful, and it means the text.
        return "eq".equals(op)
                ? partSpecValueRepository.partIdsMatchingText(orgId, def.getJsonName(), "eq", value)
                : List.of();
    }

    /**
     * Spec maps for the listed parts, in one query. The single-part {@link #toDTO(Part)} would
     * otherwise run one per row, which for a search result is the difference between one query and
     * a hundred.
     */
    private Map<Long, Map<String, Object>> specsFor(List<Part> parts) {
        if (parts.isEmpty()) return Map.of();
        return partSpecValueService.specsOf(
                parts.stream().map(Part::getId).collect(Collectors.toList()));
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

    /**
     * Maps parts to DTOs carrying their organisation-wide on-hand total and thumbnail, batching the
     * two lookups over the whole list. The BOM matching screen needs exactly this — a matched line
     * is only useful next to the stock behind it — and doing it per part would be two queries per
     * BOM line.
     */
    public List<PartDTO> toDTOsWithStock(List<Part> parts) {
        if (parts.isEmpty()) {
            return List.of();
        }
        Map<Long, Long> stockByPart = stockByOrganisation(parts);
        Map<Long, Long> thumbnailByPart = thumbnailsFor(parts);
        Map<Long, Map<String, Object>> specsByPart = specsFor(parts);
        return parts.stream()
                .map(p -> toDTOWithStock(p, stockByPart, thumbnailByPart, specsByPart))
                .collect(Collectors.toList());
    }

    private PartDTO toDTOWithStock(Part part, Map<Long, Long> stockByPart,
                                   Map<Long, Long> thumbnailByPart,
                                   Map<Long, Map<String, Object>> specsByPart) {
        PartDTO dto = toDTO(part, specsByPart.getOrDefault(part.getId(), Map.of()));
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
        Map<Long, Map<String, Object>> specsByPart = specsFor(parts);
        return parts.stream()
                .map(p -> toDTOWithStock(p, stockByPart, thumbnailByPart, specsByPart))
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

    /**
     * Create a part, and open its stock when the request carries a quantity.
     *
     * <p>The stock block is optional; a quantity without a location is rejected rather than stocked
     * somewhere chosen for the user. Both happen in one transaction, so a bad location leaves no
     * half-created part behind — the same reasoning as Quick Add, which is the other path that
     * creates a part and its stock together.
     */
    @Transactional
    public PartDTO create(PartCreateRequest request) {
        Long organisationId = currentOrganisationService.currentId();
        if (partRepository.existsByOrganisationIdAndPartNumber(organisationId, request.getPartNumber())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Part number already exists: " + request.getPartNumber());
        }
        Part part = new Part();
        // Set before buildPartFromRequest so category/tag resolution can scope to the organisation.
        part.setOrganisation(currentOrganisationService.current());
        Map<String, Object> specs = resolveSpecs(part, request);
        part = buildPartFromRequest(part, request);
        part.setCreatedBy(currentUserService.current());
        part = saveAndSync(part, specs);
        addInitialStock(part, request);
        return toDTO(part);
    }

    /**
     * Give a newly created part its opening stock, if the create request asked for any.
     *
     * <p>Routed through {@link StockMovementService#apply} like every other on-hand change, so the
     * {@code INITIAL} movement and the aggregate stay in step, and the location is remembered as
     * the user's last-used one exactly as Quick Add does.
     */
    private void addInitialStock(Part part, PartCreateRequest request) {
        if (request.getQuantity() == null) {
            return;
        }
        if (request.getLocationId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A location is required when a quantity is given");
        }
        Location location = locationRepository
                .findByIdAndOrganisationId(request.getLocationId(), currentOrganisationService.currentId())
                .orElseThrow(() -> new EntityNotFoundException("Location not found: " + request.getLocationId()));

        stockEntryRepository.save(stockMovementService.apply(part, location, request.getQuantity(),
                request.getUnitPrice(), null, MovementType.INITIAL));
        currentUserService.rememberLastLocation(location);
    }

    @Transactional
    public PartDTO update(Long id, PartRequest request) {
        Part part = requirePart(id);
        if (partRepository.existsByOrganisationIdAndPartNumberAndIdNot(
                currentOrganisationService.currentId(), request.getPartNumber(), id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Part number already exists: " + request.getPartNumber());
        }
        Map<String, Object> specs = resolveSpecs(part, request);
        return toDTO(saveAndSync(buildPartFromRequest(part, request), specs));
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

        Map<String, Object> specs = partSpecValueService.specsOf(part.getId());
        if (request.getSpecs() != null) {
            specs.putAll(specDefinitionService.canonicalizeKeys(request.getSpecs()));
        }

        if (request.getDescription() != null) part.setDescription(request.getDescription());
        if (request.getManufacturer() != null) part.setManufacturer(request.getManufacturer());
        if (request.getMpn() != null) part.setMpn(request.getMpn());
        if (request.getFootprint() != null) part.setFootprint(request.getFootprint());
        if (request.getDatasheetUrl() != null) part.setDatasheetUrl(request.getDatasheetUrl());

        return toDTO(saveAndSync(part, specs));
    }

    /**
     * Applies a chosen AI-lookup result to an existing part — the "Look up specs" action.
     *
     * <p>Same shape as {@link #applyOctopart}: specs are merged onto the part's map and a null
     * column field leaves that column alone, because both fields and specs arrive already filtered
     * to what the user ticked. It is a separate method rather than a flag on that one so neither
     * contract has to grow a "which source is this?" branch — the OctoPart path additionally sets
     * the OctoPart link, and this one has no id to set.
     */
    @Transactional
    public PartDTO applyAiLookup(Long id, com.clele.parts.dto.AiApplyRequest request) {
        Part part = requirePart(id);

        Map<String, Object> specs = partSpecValueService.specsOf(part.getId());
        if (request.getSpecs() != null) {
            specs.putAll(specDefinitionService.canonicalizeKeys(request.getSpecs()));
        }

        if (request.getDescription() != null) part.setDescription(request.getDescription());
        if (request.getDetails() != null) part.setDetails(request.getDetails());
        if (request.getManufacturer() != null) part.setManufacturer(request.getManufacturer());
        if (request.getMpn() != null) part.setMpn(request.getMpn());
        if (request.getFootprint() != null) part.setFootprint(request.getFootprint());
        if (request.getDatasheetUrl() != null) part.setDatasheetUrl(request.getDatasheetUrl());

        return toDTO(saveAndSync(part, specs));
    }

    @Transactional
    public void delete(Long id) {
        requirePart(id);
        stockEntryRepository.deleteByPartId(id);
        // Unlinks this part's attachments and drops the content no other part still uses — a shared
        // photo outlives the part it was first uploaded for.
        partAttachmentService.deleteAllForPart(id);
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
        int deleted = partRepository.deleteByIdIn(partIds);
        // The DB cascade took the attachment links with the parts; it cannot know whether the
        // content survived on another part, so sweep what nothing points at any more.
        partAttachmentService.deleteOrphans();
        return deleted;
    }

    public long countAll() {
        return partRepository.countByOrganisationId(currentOrganisationService.currentId());
    }

    /** Largest page the dashboard list will serve, whatever the caller asks for. */
    private static final int MAX_RECENT_PAGE_SIZE = 100;

    /**
     * One page of the organisation's parts, newest first — the dashboard's "Recently Added" list.
     *
     * <p>Each row carries where the part's stock sits and how much of it there is, both batched over
     * the page rather than fetched per row. The requested page is clamped into the available range,
     * so a stale page index (the list shrank while it was open) returns the last page instead of an
     * empty one.
     */
    public RecentPartsPageDTO recentlyAdded(int page, int size) {
        Long organisationId = currentOrganisationService.currentId();
        int pageSize = Math.min(Math.max(size, 1), MAX_RECENT_PAGE_SIZE);
        long total = partRepository.countByOrganisationId(organisationId);
        int lastPage = total == 0 ? 0 : (int) ((total - 1) / pageSize);
        int pageIndex = Math.min(Math.max(page, 0), lastPage);

        List<Part> parts = partRepository.findRecent(organisationId, PageRequest.of(pageIndex, pageSize));
        Map<Long, List<StockEntry>> stockByPart = stockRowsFor(parts, organisationId);

        List<RecentPartDTO> items = parts.stream()
                .map(p -> toRecentDTO(p, stockByPart.getOrDefault(p.getId(), List.of())))
                .collect(Collectors.toList());
        return RecentPartsPageDTO.builder()
                .items(items)
                .total(total)
                .page(pageIndex)
                .size(pageSize)
                .build();
    }

    /** The on-hand rows of the listed parts, grouped by part, in one query. */
    private Map<Long, List<StockEntry>> stockRowsFor(List<Part> parts, Long organisationId) {
        if (parts.isEmpty()) return Map.of();
        List<Long> ids = parts.stream().map(Part::getId).collect(Collectors.toList());
        return stockEntryRepository.findByPartIdInAndOrganisationId(ids, organisationId).stream()
                .collect(Collectors.groupingBy(s -> s.getPart().getId()));
    }

    /**
     * A part as one "Recently Added" row. The locations are ordered by how much of the part each
     * holds so the row can name the main one first and summarise the rest.
     */
    private RecentPartDTO toRecentDTO(Part part, List<StockEntry> stock) {
        return RecentPartDTO.builder()
                .id(part.getId())
                .partNumber(part.getPartNumber())
                .description(part.getDescription())
                .locations(stock.stream()
                        .sorted(Comparator.comparingInt(StockEntry::getQuantity).reversed())
                        .map(s -> s.getLocation().breadcrumb())
                        .collect(Collectors.toList()))
                .totalQuantity(stock.stream().mapToLong(StockEntry::getQuantity).sum())
                .createdAt(part.getCreatedAt())
                .build();
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

    /**
     * Save the part and mirror its specs into the typed {@code part_spec_value} rows.
     *
     * <p>Every path that writes specs goes through here, so the rows cannot fall behind the JSONB.
     * The save comes first because the rows are keyed on the part's id, which a new part does not
     * have until then. While the JSONB is still the read source this is a pure dual-write: nothing
     * user-visible depends on the rows yet, and a bug in the new path cannot lose data because
     * syncing again rebuilds them from the map.
     */
    private Part saveAndSync(Part part, Map<String, Object> specs) {
        Part saved = partRepository.save(part);
        partSpecValueService.sync(saved, specs);
        return saved;
    }

    private Part buildPartFromRequest(Part part, PartRequest request) {
        part.setPartNumber(request.getPartNumber());
        part.setDescription(request.getDescription());
        part.setDetails(request.getDetails());
        part.setManufacturer(request.getManufacturer());
        part.setPersonalNumber(request.isPersonalNumber());
        part.setDatasheetUrl(request.getDatasheetUrl());
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
        Map<String, Object> merged = part.getId() != null
                ? new LinkedHashMap<>(partSpecValueService.specsOf(part.getId()))
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

    /**
     * Single-part mapping. Specs come from {@code part_spec_value} (step 4 of the typed spec value
     * migration), which costs one extra query — use {@link #toDTO(Part, Map)} with a pre-loaded map
     * for anything mapping a list, or a page of results becomes a query per row.
     */
    public PartDTO toDTO(Part part) {
        return toDTO(part, partSpecValueService.specsOf(part.getId()));
    }

    public PartDTO toDTO(Part part, Map<String, Object> specs) {
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
                .specs(specs)
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
