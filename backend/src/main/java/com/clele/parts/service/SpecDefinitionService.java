package com.clele.parts.service;

import com.clele.parts.dto.ConvertToNumberRequest;
import com.clele.parts.dto.ConvertToNumberResult;
import com.clele.parts.dto.MergeSpecsRequest;
import com.clele.parts.dto.MoveSpecsRequest;
import com.clele.parts.dto.SpecDefinitionDTO;
import com.clele.parts.dto.SpecDefinitionRequest;
import com.clele.parts.model.Organisation;
import com.clele.parts.model.Part;
import com.clele.parts.model.SpecAlias;
import com.clele.parts.model.SpecDefinition;
import com.clele.parts.model.SpecGroup;
import com.clele.parts.repository.PartRepository;
import com.clele.parts.repository.SpecAliasRepository;
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
    private final SpecAliasRepository aliasRepo;
    private final SpecGroupService specGroupService;
    private final PartSpecValueService partSpecValueService;
    private final PartRepository partRepository;
    private final CurrentOrganisationService currentOrganisationService;
    private final ObjectMapper objectMapper;

    /** Max distinct string values for a spec to be inferred as a SELECT (enumeration). */
    private static final int SELECT_MAX_DISTINCT = 30;

    public List<SpecDefinitionDTO> findAll() {
        return specRepo.findByOrganisationIdOrderByDisplayOrderAscNameAsc(
                        currentOrganisationService.currentId()).stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    /** The spec fields filed under one group (the group detail screen). */
    public List<SpecDefinitionDTO> findByGroup(Long groupId) {
        specGroupService.requireGroup(groupId);   // 404s a group of another organisation
        return specRepo.findByGroupIdOrderByDisplayOrderAscNameAsc(groupId).stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    @Transactional
    public SpecDefinitionDTO create(SpecDefinitionRequest request) {
        SpecDefinition spec = new SpecDefinition();
        spec.setOrganisation(currentOrganisationService.current());
        applyRequest(spec, request);
        SpecDefinition saved = specRepo.save(spec);
        if (request.getAliases() != null) applyAliases(saved, request.getAliases());
        return toDTO(saved);
    }

    @Transactional
    public SpecDefinitionDTO update(Long id, SpecDefinitionRequest request) {
        SpecDefinition spec = requireSpec(id);
        applyRequest(spec, request);
        SpecDefinition saved = specRepo.save(spec);
        if (request.getAliases() != null) applyAliases(saved, request.getAliases());
        return toDTO(saved);
    }

    @Transactional
    public void delete(Long id) {
        SpecDefinition spec = requireSpec(id);
        aliasRepo.deleteAll(aliasRepo.findBySpecDefinitionIdOrderByJsonNameAsc(id));
        specRepo.delete(spec);
    }

    /**
     * Folds duplicate spec definitions into one. Each source's JSON name (and any alias it already
     * carried) becomes an alias of the target, so a later update from the source that used that name
     * still lands here; every part holding a source key has the value re-keyed onto the target's
     * JSON name; then the sources are deleted. An existing target value wins — the target is the
     * definition the user chose to keep, so its data is the data they meant to keep.
     */
    @Transactional
    public SpecDefinitionDTO merge(MergeSpecsRequest request) {
        SpecDefinition target = requireSpec(request.getTargetId());
        Organisation organisation = target.getOrganisation();

        List<SpecDefinition> sources = new ArrayList<>();
        for (Long sourceId : request.getSourceIds()) {
            if (sourceId.equals(target.getId())) continue;   // merging into itself is a no-op
            sources.add(requireSpec(sourceId));
        }
        if (sources.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Nothing to merge: no source spec other than the target");
        }

        // Re-key every part value from a source key onto the target key. The values live in
        // part_spec_value, so each part's map is read back, re-keyed and written through the same
        // sync every other path uses — the source's own rows then go with it through the
        // spec_definition FK cascade when the source is deleted below.
        Set<String> sourceKeys = sources.stream()
                .map(SpecDefinition::getJsonName)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        for (Part part : partRepository.findByOrganisationId(organisation.getId())) {
            Map<String, Object> specs =
                    new LinkedHashMap<>(partSpecValueService.specsOf(part.getId()));
            boolean touched = false;
            for (String key : sourceKeys) {
                if (!specs.containsKey(key)) continue;
                Object value = specs.remove(key);
                touched = true;
                Object existing = specs.get(target.getJsonName());
                boolean targetEmpty = existing == null || String.valueOf(existing).isBlank();
                if (targetEmpty && value != null && !String.valueOf(value).isBlank()) {
                    specs.put(target.getJsonName(), value);
                }
            }
            if (touched) partSpecValueService.sync(part, specs);
        }

        // The sources' names — and the aliases they already held — carry over to the target.
        for (SpecDefinition source : sources) {
            List<SpecAlias> theirs = aliasRepo.findBySpecDefinitionIdOrderByJsonNameAsc(source.getId());
            aliasRepo.deleteAll(theirs);
            aliasRepo.flush();
            Set<String> names = new LinkedHashSet<>();
            names.add(source.getJsonName());
            theirs.forEach(a -> names.add(a.getJsonName()));
            specRepo.delete(source);
            specRepo.flush();
            for (String name : names) {
                if (name.equals(target.getJsonName())) continue;
                aliasRepo.save(SpecAlias.builder()
                        .specDefinition(target)
                        .organisation(organisation)
                        .jsonName(name)
                        .build());
            }
        }

        return toDTO(target);
    }

    /** Move a set of spec fields into another group. */
    @Transactional
    public List<SpecDefinitionDTO> moveToGroup(MoveSpecsRequest request) {
        SpecGroup group = specGroupService.requireGroup(request.getGroupId());
        List<SpecDefinition> specs = request.getSpecIds().stream().map(this::requireSpec).toList();
        specs.forEach(spec -> spec.setGroup(group));
        return specRepo.saveAll(specs).stream().map(this::toDTO).collect(Collectors.toList());
    }

    /**
     * Rewrites the keys of an incoming spec map from any source onto the canonical JSON names,
     * resolving aliases. This is what makes merging durable: a source that keeps sending
     * {@code vsupply} lands on the spec whose canonical name is {@code supplyvoltage}. Keys that
     * match nothing are passed through untouched (an unknown spec is still worth storing — the
     * Spec Fields rescan turns it into a definition later).
     */
    public Map<String, Object> canonicalizeKeys(Map<String, Object> specs) {
        if (specs == null || specs.isEmpty()) return specs;
        Long orgId = currentOrganisationService.currentId();
        Map<String, String> byAlias = new LinkedHashMap<>();
        for (SpecAlias alias : aliasRepo.findByOrganisationIdOrderByJsonNameAsc(orgId)) {
            byAlias.put(alias.getJsonName(), alias.getSpecDefinition().getJsonName());
        }
        if (byAlias.isEmpty()) return specs;

        Map<String, Object> result = new LinkedHashMap<>();
        specs.forEach((key, value) -> {
            String canonical = byAlias.getOrDefault(key, key);
            // A canonical value already present wins over one arriving under an alias.
            Object existing = result.get(canonical);
            if (existing == null || String.valueOf(existing).isBlank()) {
                result.put(canonical, value);
            }
        });
        return result;
    }

    /** Spec fields outside the current organisation are reported as not found. */
    private SpecDefinition requireSpec(Long id) {
        return specRepo.findByIdAndOrganisationId(id, currentOrganisationService.currentId())
                .orElseThrow(() -> new EntityNotFoundException("SpecDefinition not found: " + id));
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
        Organisation organisation = currentOrganisationService.current();
        Map<String, SpecDefinition> existing = new LinkedHashMap<>();
        for (SpecDefinition def : specRepo.findByOrganisationIdOrderByDisplayOrderAscNameAsc(
                organisation.getId())) {
            existing.put(def.getJsonName(), def);
        }

        // Accumulate stats for every distinct spec key across all parts. Values come from
        // part_spec_value in one query rather than a JSONB column per part.
        Map<String, SpecStats> stats = new LinkedHashMap<>();
        List<Long> partIds = partRepository.findByOrganisationId(organisation.getId()).stream()
                .map(Part::getId).collect(Collectors.toList());
        for (Map<String, Object> specs : partSpecValueService.specsOf(partIds).values()) {
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

        // Keys that are an alias of an existing spec must not become a definition of their own —
        // that is exactly the duplicate a merge was performed to remove.
        Map<String, SpecDefinition> byAlias = new LinkedHashMap<>();
        for (SpecAlias alias : aliasRepo.findByOrganisationIdOrderByJsonNameAsc(organisation.getId())) {
            byAlias.put(alias.getJsonName(), alias.getSpecDefinition());
        }

        SpecGroup defaultGroup = specGroupService.defaultGroup();
        List<SpecDefinition> toSave = new ArrayList<>();
        for (Map.Entry<String, SpecStats> e : stats.entrySet()) {
            String jsonName = e.getKey();
            if (byAlias.containsKey(jsonName)) continue;
            SpecStats s = e.getValue();
            String dataType = s.inferType();
            String options = "SELECT".equals(dataType) ? writeOptions(s.distinctValues) : null;

            SpecDefinition def = existing.get(jsonName);
            if (def == null) {
                def = SpecDefinition.builder()
                        .organisation(organisation)
                        .jsonName(jsonName)
                        .name(SpecNameHumanizer.humanize(jsonName))
                        .displayOrder(nextOrder++)
                        .group(defaultGroup)
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
        SpecDefinition def = requireSpec(id);

        String jsonName = def.getJsonName();
        String unit = req.getUnit() == null ? "" : req.getUnit().trim();
        Map<String, String> overrides = req.getOverrides() == null ? Map.of() : req.getOverrides();

        // Collect every part that has a non-blank value for this spec.
        List<Part> parts = partRepository.findByOrganisationId(currentOrganisationService.currentId());
        List<Part> matched = new ArrayList<>();
        List<String> rawValues = new ArrayList<>();
        for (Part part : parts) {
            Map<String, Object> specs = partSpecValueService.specsOf(part.getId());
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

        // The definition changes type first, because the same string classifies differently once it
        // is a NUMBER with a unit — the sync below must see the new definition, not the old one.
        def.setDataType("NUMBER");
        def.setUnit(unit);
        def.setMetricPrefix(req.isMetricPrefix());
        def.setOptions(null);
        specRepo.saveAndFlush(def);

        resolved.forEach((part, base) -> {
            Map<String, Object> specs =
                    new LinkedHashMap<>(partSpecValueService.specsOf(part.getId()));
            specs.put(jsonName, base);
            partSpecValueService.sync(part, specs);
        });

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


    private void applyRequest(SpecDefinition spec, SpecDefinitionRequest request) {
        spec.setJsonName(request.getJsonName());
        spec.setName(request.getName());
        spec.setDataType(request.getDataType() != null ? request.getDataType() : "TEXT");
        spec.setUnit(request.getUnit());
        spec.setMetricPrefix(request.isMetricPrefix());
        // Blank means "no family", i.e. never parse this field's values — an empty string would be
        // an unknown code, which UnitFamily.byCode resolves to nothing anyway, but storing null says
        // it deliberately.
        spec.setUnitFamily(request.getUnitFamily() == null || request.getUnitFamily().isBlank()
                ? null : request.getUnitFamily().trim());
        spec.setDisplayOrder(request.getDisplayOrder());
        spec.setGroup(request.getGroupId() != null
                ? specGroupService.requireGroup(request.getGroupId())
                : specGroupService.defaultGroup());

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

    /**
     * Replaces a spec's aliases with the requested list. Each must be unique within the
     * organisation — an alias colliding with another spec's canonical name or alias would make
     * incoming data ambiguous, so it is rejected rather than silently repointed.
     */
    private void applyAliases(SpecDefinition spec, List<String> requested) {
        Long orgId = currentOrganisationService.currentId();
        List<SpecAlias> existing = spec.getId() == null
                ? List.of()
                : aliasRepo.findBySpecDefinitionIdOrderByJsonNameAsc(spec.getId());

        Set<String> wanted = requested.stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty() && !s.equals(spec.getJsonName()))
                .collect(Collectors.toCollection(LinkedHashSet::new));

        // Drop the ones no longer wanted first, so an alias can be moved between specs in one edit.
        List<SpecAlias> removed = existing.stream()
                .filter(a -> !wanted.contains(a.getJsonName()))
                .toList();
        aliasRepo.deleteAll(removed);
        aliasRepo.flush();

        Set<String> kept = existing.stream()
                .filter(a -> wanted.contains(a.getJsonName()))
                .map(SpecAlias::getJsonName)
                .collect(Collectors.toSet());

        for (String name : wanted) {
            if (kept.contains(name)) continue;
            if (specRepo.findByOrganisationIdAndJsonName(orgId, name).isPresent()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "'" + name + "' is already the JSON name of another spec field. "
                                + "Merge the two spec fields instead.");
            }
            if (aliasRepo.findByOrganisationIdAndJsonName(orgId, name).isPresent()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "'" + name + "' is already an alias of another spec field");
            }
            aliasRepo.save(SpecAlias.builder()
                    .specDefinition(spec)
                    .organisation(spec.getOrganisation())
                    .jsonName(name)
                    .build());
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
                .unitFamily(spec.getUnitFamily())
                .options(options.isEmpty() ? null : options)
                .displayOrder(spec.getDisplayOrder())
                .groupId(spec.getGroup() != null ? spec.getGroup().getId() : null)
                .groupName(spec.getGroup() != null ? spec.getGroup().getName() : null)
                .aliases(spec.getId() == null ? List.of()
                        : aliasRepo.findBySpecDefinitionIdOrderByJsonNameAsc(spec.getId()).stream()
                                .map(SpecAlias::getJsonName)
                                .collect(Collectors.toList()))
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
