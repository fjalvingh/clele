package com.clele.parts.service;

import com.clele.parts.dto.PartKitGenerateRequest;
import com.clele.parts.dto.PartKitGenerateResultDTO;
import com.clele.parts.dto.PartKitTemplateDTO;
import com.clele.parts.dto.PartKitTemplateRequest;
import com.clele.parts.model.Category;
import com.clele.parts.model.Location;
import com.clele.parts.model.MovementType;
import com.clele.parts.model.Part;
import com.clele.parts.model.PartKitTemplate;
import com.clele.parts.model.PartKitTemplateValue;
import com.clele.parts.model.StockEntry;
import com.clele.parts.repository.CategoryRepository;
import com.clele.parts.repository.LocationRepository;
import com.clele.parts.repository.PartKitTemplateRepository;
import com.clele.parts.repository.PartRepository;
import com.clele.parts.repository.StockEntryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Part kit templates: define a pack of parts that differ in one value once, then expand it.
 *
 * <p>The template holds the part fields with {@code ${value}} standing in for the varying part, and
 * a list of the values. Generating substitutes one value into every field to make one part.
 *
 * <p><b>Generating finds before it creates, and never rewrites a part it found.</b> A kit is bought
 * more than once — the second pack of the same resistor assortment must add stock to the parts that
 * already exist, not fail on the unique part number and not quietly overwrite a description someone
 * has since corrected by hand. The template describes how a part is <em>born</em>, not what it must
 * keep looking like.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PartKitTemplateService {

    /** The one placeholder. Deliberately literal and case-sensitive — no expression language. */
    public static final String PLACEHOLDER = "${value}";

    private final PartKitTemplateRepository templateRepository;
    private final PartRepository partRepository;
    private final CategoryRepository categoryRepository;
    private final LocationRepository locationRepository;
    private final StockEntryRepository stockEntryRepository;
    private final CurrentOrganisationService currentOrganisationService;
    private final CurrentUserService currentUserService;
    private final StockMovementService stockMovementService;
    private final SpecDefinitionService specDefinitionService;
    private final TagService tagService;

    // ------------------------------------------------------------------
    // CRUD
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<PartKitTemplateDTO> findAll() {
        return templateRepository
                .findByOrganisationIdOrderByNameAsc(currentOrganisationService.currentId())
                .stream()
                .map(this::toDTO)
                .toList();
    }

    @Transactional(readOnly = true)
    public PartKitTemplateDTO findById(Long id) {
        return toDTO(require(id));
    }

    @Transactional
    public PartKitTemplateDTO create(PartKitTemplateRequest request) {
        Long organisationId = currentOrganisationService.currentId();
        validate(request);
        if (templateRepository.existsByOrganisationIdAndNameIgnoreCase(organisationId, request.getName().trim())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A kit template named '" + request.getName().trim() + "' already exists");
        }
        PartKitTemplate template = new PartKitTemplate();
        template.setOrganisation(currentOrganisationService.current());
        template.setCreatedBy(currentUserService.current());
        apply(template, request);
        return toDTO(templateRepository.save(template));
    }

    @Transactional
    public PartKitTemplateDTO update(Long id, PartKitTemplateRequest request) {
        PartKitTemplate template = require(id);
        validate(request);
        if (templateRepository.existsByOrganisationIdAndNameIgnoreCaseAndIdNot(
                currentOrganisationService.currentId(), request.getName().trim(), id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A kit template named '" + request.getName().trim() + "' already exists");
        }
        apply(template, request);
        return toDTO(templateRepository.save(template));
    }

    @Transactional
    public void delete(Long id) {
        templateRepository.delete(require(id));
    }

    // ------------------------------------------------------------------
    // Generate
    // ------------------------------------------------------------------

    /**
     * Expand the template into parts and add stock to each.
     *
     * <p>One transaction for the whole run: a half-generated kit is worse than none, since the user
     * cannot tell from the parts list which values were reached.
     */
    @Transactional
    public PartKitGenerateResultDTO generate(Long id, PartKitGenerateRequest request) {
        PartKitTemplate template = require(id);
        Long organisationId = currentOrganisationService.currentId();

        if (template.getValues().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "This kit template has no values to generate from");
        }

        Location location = locationRepository
                .findByIdAndOrganisationId(request.getLocationId(), organisationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Location not found: " + request.getLocationId()));

        int created = 0;
        int found = 0;
        int stockAdded = 0;
        List<PartKitGenerateResultDTO.Line> lines = new ArrayList<>();

        for (PartKitTemplateValue value : template.getValues()) {
            String v = value.getValue();
            String partNumber = substitute(template.getPartNumberTemplate(), v);
            if (partNumber == null || partNumber.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "The part number template produced an empty part number for value '" + v + "'");
            }

            var existing = partRepository.findByOrganisationIdAndPartNumber(organisationId, partNumber);
            Part part;
            boolean isNew = existing.isEmpty();
            if (isNew) {
                part = partRepository.save(buildPart(template, v, partNumber));
                created++;
            } else {
                part = existing.get();
                found++;
            }

            int qty = request.getQuantityPerValue() == null ? 0 : request.getQuantityPerValue();
            if (qty > 0) {
                // A part born here gets INITIAL; one that was already in the catalogue is being
                // restocked, which is a PURCHASE. Same distinction the manual paths draw.
                StockEntry entry = stockMovementService.apply(part, location, qty,
                        request.getUnitPrice(), "Generated from kit template: " + template.getName(),
                        isNew ? MovementType.INITIAL : MovementType.PURCHASE);
                stockEntryRepository.save(entry);
                stockAdded += qty;
            }

            lines.add(PartKitGenerateResultDTO.Line.builder()
                    .value(v)
                    .partId(part.getId())
                    .partNumber(part.getPartNumber())
                    .created(isNew)
                    .quantityAdded(qty)
                    .build());
        }

        if (request.getQuantityPerValue() != null && request.getQuantityPerValue() > 0) {
            currentUserService.rememberLastLocation(location);
        }

        log.info("Kit template {} generated {} new / {} existing parts, {} units at location {}",
                template.getId(), created, found, stockAdded, location.getId());

        return PartKitGenerateResultDTO.builder()
                .partsCreated(created)
                .partsFound(found)
                .stockAdded(stockAdded)
                .lines(lines)
                .build();
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    /** Replace every occurrence of {@code ${value}}. Null and blank templates stay null. */
    public static String substitute(String template, String value) {
        if (template == null) return null;
        String result = template.replace(PLACEHOLDER, value == null ? "" : value);
        return result.isBlank() ? null : result;
    }

    private Part buildPart(PartKitTemplate template, String value, String partNumber) {
        Part part = new Part();
        part.setOrganisation(template.getOrganisation());
        part.setPartNumber(partNumber);
        part.setPersonalNumber(template.isPersonalNumber());
        part.setManufacturer(substitute(template.getManufacturerTemplate(), value));
        part.setDescription(substitute(template.getDescriptionTemplate(), value));
        part.setDetails(substitute(template.getDetailsTemplate(), value));
        part.setFootprint(substitute(template.getFootprintTemplate(), value));
        part.setDatasheetUrl(substitute(template.getDatasheetUrlTemplate(), value));
        part.setCategory(template.getCategory());
        part.setCreatedBy(currentUserService.current());

        Map<String, Object> specs = new LinkedHashMap<>();
        if (template.getSpecs() != null) {
            for (Map.Entry<String, Object> e : template.getSpecs().entrySet()) {
                String substituted = substitute(e.getValue() == null ? null : String.valueOf(e.getValue()), value);
                if (substituted != null) specs.put(e.getKey(), substituted);
            }
        }
        // Same landing rule as every other intake path: keys are resolved onto their canonical
        // spec name (and its aliases) before they are stored.
        part.setSpecs(specDefinitionService.canonicalizeKeys(specs));

        List<String> tags = template.getTags().stream()
                .map(t -> substitute(t, value))
                .filter(t -> t != null && !t.isBlank())
                .toList();
        if (!tags.isEmpty()) {
            part.getTags().addAll(tagService.resolveOrCreate(tags));
        }
        return part;
    }

    private void validate(PartKitTemplateRequest request) {
        String partNumberTemplate = request.getPartNumberTemplate() == null
                ? "" : request.getPartNumberTemplate().trim();
        // Part numbers are unique per organisation, so a template whose part number does not vary
        // generates exactly one part however many values it lists — every value after the first
        // would silently pile its stock onto the same part. Refuse it rather than explain it later.
        if (!partNumberTemplate.contains(PLACEHOLDER)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "The part number template must contain " + PLACEHOLDER
                            + " — otherwise every value would generate the same part");
        }
    }

    private void apply(PartKitTemplate template, PartKitTemplateRequest request) {
        template.setName(request.getName().trim());
        template.setNotes(blankToNull(request.getNotes()));
        template.setPartNumberTemplate(request.getPartNumberTemplate().trim());
        template.setPersonalNumber(request.isPersonalNumber());
        template.setManufacturerTemplate(blankToNull(request.getManufacturerTemplate()));
        template.setDescriptionTemplate(blankToNull(request.getDescriptionTemplate()));
        template.setDetailsTemplate(blankToNull(request.getDetailsTemplate()));
        template.setFootprintTemplate(blankToNull(request.getFootprintTemplate()));
        template.setDatasheetUrlTemplate(blankToNull(request.getDatasheetUrlTemplate()));

        if (request.getCategoryId() == null) {
            template.setCategory(null);
        } else {
            Category category = categoryRepository
                    .findByIdAndOrganisationId(request.getCategoryId(), currentOrganisationService.currentId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                            "Category not found: " + request.getCategoryId()));
            template.setCategory(category);
        }

        Map<String, Object> specs = new LinkedHashMap<>();
        if (request.getSpecs() != null) {
            for (Map.Entry<String, Object> e : request.getSpecs().entrySet()) {
                if (e.getValue() == null) continue;
                String v = String.valueOf(e.getValue());
                if (!v.isBlank()) specs.put(e.getKey(), v);
            }
        }
        template.setSpecs(specs);

        Set<String> tags = new LinkedHashSet<>();
        if (request.getTags() != null) {
            for (String t : request.getTags()) {
                if (t != null && !t.isBlank()) tags.add(t.trim());
            }
        }
        template.getTags().clear();
        template.getTags().addAll(tags);

        applyValues(template, request.getValues());
    }

    /**
     * Rewrite the value list to exactly what was sent, keeping the rows that survive.
     *
     * <p>Reusing the existing rows rather than clearing and re-adding matters: {@code orphanRemoval}
     * plus a unique {@code (template_id, value)} means a delete-then-insert of the same value inside
     * one transaction can hit the constraint before the delete is flushed.
     */
    private void applyValues(PartKitTemplate template, List<String> requested) {
        List<String> wanted = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (requested != null) {
            for (String v : requested) {
                if (v == null) continue;
                String trimmed = v.trim();
                if (!trimmed.isEmpty() && seen.add(trimmed)) wanted.add(trimmed);
            }
        }

        Map<String, PartKitTemplateValue> byValue = new LinkedHashMap<>();
        for (PartKitTemplateValue existing : template.getValues()) {
            byValue.put(existing.getValue(), existing);
        }

        List<PartKitTemplateValue> next = new ArrayList<>();
        for (int i = 0; i < wanted.size(); i++) {
            String v = wanted.get(i);
            PartKitTemplateValue row = byValue.get(v);
            if (row == null) {
                row = PartKitTemplateValue.builder().template(template).value(v).build();
            }
            row.setDisplayOrder(i);
            next.add(row);
        }
        template.getValues().clear();
        template.getValues().addAll(next);
    }

    private PartKitTemplate require(Long id) {
        return templateRepository
                .findByIdAndOrganisationId(id, currentOrganisationService.currentId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Kit template not found: " + id));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String breadcrumb(Category category) {
        if (category == null) return null;
        List<String> parts = new ArrayList<>();
        for (Category c = category; c != null; c = c.getParent()) {
            parts.add(0, c.getName());
        }
        return String.join(" > ", parts);
    }

    private PartKitTemplateDTO toDTO(PartKitTemplate t) {
        return PartKitTemplateDTO.builder()
                .id(t.getId())
                .name(t.getName())
                .notes(t.getNotes())
                .partNumberTemplate(t.getPartNumberTemplate())
                .personalNumber(t.isPersonalNumber())
                .manufacturerTemplate(t.getManufacturerTemplate())
                .descriptionTemplate(t.getDescriptionTemplate())
                .detailsTemplate(t.getDetailsTemplate())
                .footprintTemplate(t.getFootprintTemplate())
                .datasheetUrlTemplate(t.getDatasheetUrlTemplate())
                .categoryId(t.getCategory() == null ? null : t.getCategory().getId())
                .categoryName(t.getCategory() == null ? null : t.getCategory().getName())
                .categoryBreadcrumb(breadcrumb(t.getCategory()))
                .specs(t.getSpecs() == null ? Map.of() : t.getSpecs())
                .tags(List.copyOf(t.getTags()))
                .values(t.getValues().stream().map(PartKitTemplateValue::getValue).toList())
                .createdByName(t.getCreatedBy() == null ? null : t.getCreatedBy().getFullName())
                .createdAt(t.getCreatedAt())
                .updatedAt(t.getUpdatedAt())
                .build();
    }
}
