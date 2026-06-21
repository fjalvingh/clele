package com.clele.parts.service;

import com.clele.parts.dto.ConvertToNumberRequest;
import com.clele.parts.dto.ConvertToNumberResult;
import com.clele.parts.dto.SpecDefinitionDTO;
import com.clele.parts.dto.SpecDefinitionRequest;
import com.clele.parts.model.Category;
import com.clele.parts.model.Part;
import com.clele.parts.model.SpecDefinition;
import com.clele.parts.repository.CategoryRepository;
import com.clele.parts.repository.PartRepository;
import com.clele.parts.repository.SpecDefinitionRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SpecDefinitionService {

    private final SpecDefinitionRepository specRepo;
    private final CategoryRepository categoryRepository;
    private final PartRepository partRepository;
    private final ObjectMapper objectMapper;

    /** Max distinct string values for a spec to be inferred as a SELECT (enumeration). */
    private static final int SELECT_MAX_DISTINCT = 30;

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
     * Scans the {@code specs} JSONB of every part and upserts a spec_definition per distinct
     * JSON key, inferring the data type and collecting possible values. All definitions are held
     * in memory while scanning. Existing definitions are matched by {@code jsonName}: their
     * manually-edited title and unit are preserved while the inferred dataType/options are
     * refreshed. New keys are created with a humanized title. Definitions whose key no longer
     * appears in any part are left untouched.
     */
    @Transactional
    public List<SpecDefinitionDTO> rescanFromParts() {
        // Hold all existing definitions in memory, keyed by json_name.
        Map<String, SpecDefinition> existing = new LinkedHashMap<>();
        for (SpecDefinition def : specRepo.findAll()) {
            existing.put(def.getJsonName(), def);
        }

        // Accumulate stats for every distinct spec key across all parts.
        Map<String, SpecStats> stats = new LinkedHashMap<>();
        for (Part part : partRepository.findAll()) {
            Map<String, Object> specs = part.getSpecs();
            if (specs == null) continue;
            for (Map.Entry<String, Object> e : specs.entrySet()) {
                Object value = e.getValue();
                if (value == null) continue;
                String str = String.valueOf(value);
                if (str.isBlank()) continue;
                stats.computeIfAbsent(e.getKey(), k -> new SpecStats()).observe(value, str);
            }
        }

        int nextOrder = existing.values().stream()
                .mapToInt(SpecDefinition::getDisplayOrder).max().orElse(0) + 1;

        List<SpecDefinition> toSave = new ArrayList<>();
        for (Map.Entry<String, SpecStats> e : stats.entrySet()) {
            String jsonName = e.getKey();
            SpecStats s = e.getValue();
            String dataType = s.inferType();
            String options = "SELECT".equals(dataType) ? writeOptions(s.distinctValues) : null;

            SpecDefinition def = existing.get(jsonName);
            if (def == null) {
                def = SpecDefinition.builder()
                        .jsonName(jsonName)
                        .name(SpecNameHumanizer.humanize(jsonName))
                        .displayOrder(nextOrder++)
                        .majorType("TECHNICAL")
                        .build();
            }
            // Preserve name/unit/displayOrder; refresh inferred type + options.
            def.setDataType(dataType);
            def.setOptions(options);
            toSave.add(def);
        }

        specRepo.saveAll(toSave);
        return findAll();
    }

    /**
     * Converts a TEXT spec definition to NUMBER by parsing every part's value for this spec into a chosen
     * base unit (e.g. "9 mA" -> "0.009" in base "A"). Dry-run (commit=false) scans and reports how many
     * parse and which distinct values fail; commit=true requires zero failures (after applying the
     * caller's overrides), rewrites the matched part values, and flips the definition to NUMBER.
     */
    @Transactional
    public ConvertToNumberResult convertToNumber(Long id, ConvertToNumberRequest req) {
        SpecDefinition def = specRepo.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("SpecDefinition not found: " + id));

        String jsonName = def.getJsonName();
        String unit = req.getUnit() == null ? "" : req.getUnit().trim();
        Map<String, String> overrides = req.getOverrides() == null ? Map.of() : req.getOverrides();

        // Collect every part that has a non-blank value for this spec.
        List<Part> parts = partRepository.findAll();
        List<Part> matched = new ArrayList<>();
        List<String> rawValues = new ArrayList<>();
        for (Part part : parts) {
            Map<String, Object> specs = part.getSpecs();
            if (specs == null || !specs.containsKey(jsonName)) continue;
            Object value = specs.get(jsonName);
            if (value == null) continue;
            String str = String.valueOf(value);
            if (str.isBlank()) continue;
            matched.add(part);
            rawValues.add(str);
        }

        // Blank unit: only suggest one — without a base unit we can't tell which values parse, so
        // report no failures (an empty unit must not flag every value as unparseable).
        if (unit.isEmpty()) {
            return ConvertToNumberResult.builder()
                    .total(matched.size())
                    .converted(0)
                    .suggestedUnit(MetricUnitParser.suggestUnit(rawValues))
                    .failures(List.of())
                    .build();
        }

        // Parse each value (after applying any override for its original text).
        int converted = 0;
        Map<Part, String> resolved = new LinkedHashMap<>();
        for (int i = 0; i < matched.size(); i++) {
            String raw = rawValues.get(i);
            String effective = overrides.getOrDefault(raw, raw);
            Optional<String> base = MetricUnitParser.parseToBase(effective, unit);
            if (base.isPresent()) {
                converted++;
                resolved.put(matched.get(i), base.get());
            }
        }
        List<ConvertToNumberResult.Failure> failures = groupFailures(rawValues, overrides, unit);

        if (!req.isCommit()) {
            return ConvertToNumberResult.builder()
                    .total(matched.size())
                    .converted(converted)
                    .failures(failures)
                    .build();
        }

        // Commit guard: every value must parse.
        if (!failures.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Cannot convert: " + failures.size() + " value(s) still fail to parse");
        }

        resolved.forEach((part, base) -> part.getSpecs().put(jsonName, base));
        partRepository.saveAll(resolved.keySet());

        def.setDataType("NUMBER");
        def.setUnit(unit);
        def.setMetricPrefix(req.isMetricPrefix());
        def.setOptions(null);
        specRepo.save(def);

        return ConvertToNumberResult.builder()
                .total(matched.size())
                .converted(converted)
                .failures(List.of())
                .definition(toDTO(def))
                .build();
    }

    /** Distinct values that fail to parse into {@code unit} (after overrides), with occurrence counts. */
    private List<ConvertToNumberResult.Failure> groupFailures(
            List<String> rawValues, Map<String, String> overrides, String unit) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String raw : rawValues) {
            String effective = overrides.getOrDefault(raw, raw);
            boolean ok = !unit.isEmpty() && MetricUnitParser.parseToBase(effective, unit).isPresent();
            if (!ok) counts.merge(raw, 1, Integer::sum);
        }
        return counts.entrySet().stream()
                .map(e -> new ConvertToNumberResult.Failure(e.getKey(), e.getValue()))
                .collect(Collectors.toList());
    }

    private String writeOptions(Set<String> values) {
        try {
            return objectMapper.writeValueAsString(new ArrayList<>(values));
        } catch (JsonProcessingException ex) {
            return null;
        }
    }

    /** Per-key value statistics collected during a rescan. */
    private static final class SpecStats {
        final Set<String> distinctValues = new LinkedHashSet<>();
        boolean allBoolean = true;
        boolean allNumeric = true;
        boolean anyValueContainsDigit = false;

        void observe(Object value, String str) {
            distinctValues.add(str);
            if (!isBoolean(value, str)) allBoolean = false;
            if (!isNumeric(value, str)) allNumeric = false;
            if (containsDigit(str)) anyValueContainsDigit = true;
        }

        String inferType() {
            if (allBoolean) return "BOOLEAN";
            if (allNumeric) return "NUMBER";
            if (distinctValues.size() <= SELECT_MAX_DISTINCT && !anyValueContainsDigit) {
                return "SELECT";
            }
            return "TEXT";
        }

        private static boolean isBoolean(Object value, String str) {
            return value instanceof Boolean
                    || "true".equalsIgnoreCase(str) || "false".equalsIgnoreCase(str);
        }

        private static boolean isNumeric(Object value, String str) {
            if (value instanceof Number) return true;
            try {
                Double.parseDouble(str);
                return true;
            } catch (NumberFormatException ex) {
                return false;
            }
        }

        private static boolean containsDigit(String str) {
            for (int i = 0; i < str.length(); i++) {
                if (Character.isDigit(str.charAt(i))) return true;
            }
            return false;
        }
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
        spec.setJsonName(request.getJsonName());
        spec.setName(request.getName());
        spec.setDataType(request.getDataType() != null ? request.getDataType() : "TEXT");
        spec.setUnit(request.getUnit());
        spec.setMetricPrefix(request.isMetricPrefix());
        spec.setDisplayOrder(request.getDisplayOrder());
        spec.setMajorType(request.getMajorType() != null ? request.getMajorType() : "TECHNICAL");

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
                .jsonName(spec.getJsonName())
                .name(spec.getName())
                .dataType(spec.getDataType())
                .unit(spec.getUnit())
                .metricPrefix(spec.isMetricPrefix())
                .options(options.isEmpty() ? null : options)
                .displayOrder(spec.getDisplayOrder())
                .majorType(spec.getMajorType())
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
