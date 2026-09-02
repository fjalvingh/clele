package com.clele.parts.mcp;

import com.clele.parts.dto.CategoryDTO;
import com.clele.parts.dto.CategoryTreeDTO;
import com.clele.parts.dto.LocationDTO;
import com.clele.parts.dto.PartDTO;
import com.clele.parts.dto.SpecDefinitionDTO;
import com.clele.parts.dto.StockEntryDTO;
import com.clele.parts.dto.StockThresholdDTO;
import com.clele.parts.model.SpecDefinition;
import com.clele.parts.repository.SpecDefinitionRepository;
import com.clele.parts.service.CategoryService;
import com.clele.parts.service.CurrentOrganisationService;
import com.clele.parts.service.LocationService;
import com.clele.parts.service.PartService;
import com.clele.parts.service.SpecDefinitionService;
import com.clele.parts.service.StockEntryService;
import com.clele.parts.service.StockThresholdService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The read-only tools the MCP endpoint offers, and what each one does.
 *
 * <p>Every tool goes through the ordinary services, so the organisation scoping, the parametric
 * spec search and the stock aggregate are the same code the web UI runs — an answer here cannot
 * drift from what the screen shows.
 *
 * <p>Two shaping rules run through all of it, both about a model's context rather than a screen's
 * pixels. <b>Results are capped and say so</b>: a catalogue query that matches 900 parts returns
 * the first page and the true total, because silently truncating teaches the model a wrong fact
 * about the inventory. And <b>a spec value travels twice</b> — raw for comparing, rendered for
 * reading (see {@link SpecValueRenderer}).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class McpToolRegistry {

    /** The operators a spec criterion may use — the set {@code PartService.applySpecCriteria} honours. */
    private static final Set<String> SPEC_OPS =
            Set.of("eq", "gte", "gt", "lte", "lt", "contains", "any");

    private static final int DEFAULT_PART_LIMIT = 25;
    private static final int MAX_PART_LIMIT = 200;
    private static final int DEFAULT_FIELD_LIMIT = 100;
    private static final int MAX_FIELD_LIMIT = 1000;

    private final ObjectMapper objectMapper;
    private final PartService partService;
    private final StockEntryService stockEntryService;
    private final StockThresholdService stockThresholdService;
    private final SpecDefinitionService specDefinitionService;
    private final SpecDefinitionRepository specDefinitionRepository;
    private final CategoryService categoryService;
    private final LocationService locationService;
    private final CurrentOrganisationService currentOrganisationService;

    /** A tool as {@code tools/list} renders it, plus the code behind it. */
    public record Tool(String name, String title, String description, JsonNode inputSchema,
                       Function<JsonNode, Object> handler) {}

    /** A name no tool answers to. A protocol-level mistake, not a failed query. */
    public static class UnknownToolException extends RuntimeException {
        UnknownToolException(String message) {
            super(message);
        }
    }

    /** Something the caller asked for that cannot be honoured — reported as a failed tool result. */
    private static class ToolArgumentException extends RuntimeException {
        ToolArgumentException(String message) {
            super(message);
        }
    }

    private List<Tool> tools;

    public synchronized List<Tool> list() {
        if (tools == null) {
            tools = build();
        }
        return tools;
    }

    /**
     * Run one tool. A failure inside a tool comes back as a tool result marked {@code isError} —
     * that is the MCP convention, and it is the useful one: the model sees what went wrong and can
     * correct its own arguments, where a transport error would just end the exchange.
     */
    public ObjectNode call(String name, JsonNode arguments) {
        Tool tool = list().stream()
                .filter(t -> t.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new UnknownToolException("Unknown tool: " + name));
        JsonNode args = (arguments == null || arguments.isNull())
                ? objectMapper.createObjectNode()
                : arguments;
        try {
            return content(tool.handler().apply(args), false);
        } catch (ToolArgumentException e) {
            return content(Map.of("error", e.getMessage()), true);
        } catch (Exception e) {
            log.warn("MCP tool '{}' failed", name, e);
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return content(Map.of("error", message), true);
        }
    }

    private ObjectNode content(Object payload, boolean isError) {
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode content = result.putArray("content");
        ObjectNode block = content.addObject();
        block.put("type", "text");
        block.put("text", json(payload));
        result.put("isError", isError);
        return result;
    }

    private String json(Object payload) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise tool result", e);
        }
    }

    // ---------------------------------------------------------------- the tools

    private List<Tool> build() {
        return List.of(
                new Tool("search_parts", "Search parts",
                        """
                        Search the parts catalogue. Filters combine with AND. The `spec` filter is \
                        the parametric one and the reason this catalogue exists: each criterion is \
                        "<field>:<op>:<value>", where <field> is a spec field's jsonName (see \
                        list_spec_fields) and <op> is one of eq, gte, gt, lte, lt, contains, any. \
                        Values may be written the way an engineer writes them (4k7, 100nF, 1e-7). \
                        A criterion asks whether the part has *some* value satisfying it, so a part \
                        specified "2..5.5" does match supplyvoltage:eq:3.3. An unknown field name \
                        matches nothing rather than being ignored. Returns a capped page plus the \
                        true total; use get_part for a part's full specifications and stock.""",
                        schema("""
                               {
                                 "type": "object",
                                 "properties": {
                                   "query": {"type": "string", "description": "Free text over part number, description, details, manufacturer and textual spec values."},
                                   "spec": {"type": "array", "items": {"type": "string"}, "description": "Parametric criteria, e.g. [\\"supplyvoltage:gte:3.3\\", \\"package:eq:SOT-23\\"]."},
                                   "category": {"type": "string", "description": "Category name; matches that category and everything below it."},
                                   "categoryId": {"type": "integer", "description": "Category id, from list_categories. Takes precedence over `category`."},
                                   "manufacturer": {"type": "string", "description": "Case-insensitive substring of the manufacturer."},
                                   "tags": {"type": "array", "items": {"type": "string"}, "description": "A part must carry all of these tags."},
                                   "inStockOnly": {"type": "boolean", "description": "Only parts with stock on hand. Default false."},
                                   "limit": {"type": "integer", "description": "Max parts to return, 1-200. Default 25."}
                                 }
                               }
                               """),
                        this::searchParts),

                new Tool("get_part", "Get one part",
                        """
                        One part in full: its identification, every specification (raw value plus a \
                        rendered one), the stock held per location, and its tags. Identify it by \
                        `id` (from search_parts) or by `partNumber`.""",
                        schema("""
                               {
                                 "type": "object",
                                 "properties": {
                                   "id": {"type": "integer", "description": "The part id, as returned by search_parts."},
                                   "partNumber": {"type": "string", "description": "Exact part number; used when `id` is absent."}
                                 }
                               }
                               """),
                        this::getPart),

                new Tool("list_spec_fields", "List specification fields",
                        """
                        The specification fields this catalogue knows, with the jsonName that \
                        search_parts' `spec` filter expects. Call this before building a \
                        parametric search: a guessed field name matches nothing. A NUMBER field's \
                        values are stored in the base SI unit of its unitFamily; TEXT, SELECT and \
                        BOOLEAN fields are compared as text (use the eq or contains operators).""",
                        schema("""
                               {
                                 "type": "object",
                                 "properties": {
                                   "filter": {"type": "string", "description": "Case-insensitive substring of the field's jsonName or label."},
                                   "limit": {"type": "integer", "description": "Max fields to return, 1-1000. Default 100."}
                                 }
                               }
                               """),
                        this::listSpecFields),

                new Tool("list_categories", "List categories",
                        """
                        The category tree, flattened, each with its full breadcrumb and how many \
                        parts sit in it. Categories nest, and a search by category includes \
                        everything below it.""",
                        schema("""
                               {"type": "object", "properties": {}}
                               """),
                        args -> listCategories()),

                new Tool("list_locations", "List storage locations",
                        """
                        The storage locations stock can be held in, flattened from their tree, each \
                        with its full breadcrumb.""",
                        schema("""
                               {"type": "object", "properties": {}}
                               """),
                        args -> listLocations()),

                new Tool("list_low_stock", "List parts below their threshold",
                        """
                        Parts whose on-hand quantity has fallen below the minimum set for them — \
                        what needs reordering.""",
                        schema("""
                               {"type": "object", "properties": {}}
                               """),
                        args -> listLowStock()));
    }

    // ---------------------------------------------------------------- handlers

    private Object searchParts(JsonNode args) {
        // Boxed on both branches deliberately: a ternary that mixes long with Long unboxes the
        // Long, and "no category" is exactly the null that would throw.
        Long categoryId = args.hasNonNull("categoryId")
                ? Long.valueOf(args.get("categoryId").asLong())
                : resolveCategory(text(args, "category"));
        List<String> specs = strings(args, "spec");
        List<String> tags = strings(args, "tags");
        validateSpecCriteria(specs);

        List<PartDTO> found = partService.search(text(args, "query"), categoryId, "partNumber",
                null, text(args, "manufacturer"), null, null,
                tags.isEmpty() ? null : tags, specs.isEmpty() ? null : specs);

        if (args.path("inStockOnly").asBoolean(false)) {
            found = found.stream()
                    .filter(p -> p.getTotalQuantity() != null && p.getTotalQuantity() > 0)
                    .toList();
        }

        int limit = bounded(args, "limit", DEFAULT_PART_LIMIT, MAX_PART_LIMIT);
        List<Map<String, Object>> rows = found.stream()
                .limit(limit)
                .map(this::summarise)
                .toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", found.size());
        result.put("returned", rows.size());
        if (rows.size() < found.size()) {
            result.put("note", "Showing the first " + rows.size() + " of " + found.size()
                    + " matches; narrow the search or raise `limit` (max " + MAX_PART_LIMIT + ").");
        }
        result.put("parts", rows);
        return result;
    }

    private Object getPart(JsonNode args) {
        PartDTO part = args.hasNonNull("id")
                ? partService.findById(args.get("id").asLong())
                : byPartNumber(text(args, "partNumber"));

        Map<String, SpecDefinition> definitions = definitionsByJsonName();
        List<Map<String, Object>> specs = new ArrayList<>();
        if (part.getSpecs() != null) {
            part.getSpecs().forEach((jsonName, raw) -> {
                SpecDefinition definition = definitions.get(jsonName);
                Map<String, Object> spec = new LinkedHashMap<>();
                spec.put("field", jsonName);
                spec.put("label", definition == null ? jsonName : definition.getName());
                spec.put("value", raw);
                spec.put("display", SpecValueRenderer.display(raw, definition));
                if (definition != null) {
                    spec.put("dataType", definition.getDataType());
                    if (definition.getUnitFamily() != null) {
                        spec.put("unitFamily", definition.getUnitFamily());
                    }
                    if (definition.getUnit() != null && !definition.getUnit().isBlank()) {
                        spec.put("unit", definition.getUnit());
                    }
                }
                specs.add(spec);
            });
        }

        List<StockEntryDTO> entries = stockEntryService.findByPartId(part.getId());
        List<Map<String, Object>> stock = entries.stream().map(this::stockRow).toList();
        // Summed here rather than taken from the DTO: only the list paths fill in totalQuantity, and
        // a null there reads as "none in stock" to anything that cannot see the difference.
        long onHand = entries.stream()
                .mapToLong(entry -> entry.getQuantity() == null ? 0L : entry.getQuantity())
                .sum();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", part.getId());
        result.put("partNumber", part.getPartNumber());
        result.put("description", part.getDescription());
        putIfPresent(result, "details", part.getDetails());
        putIfPresent(result, "manufacturer", part.getManufacturer());
        putIfPresent(result, "mpn", part.getMpn());
        putIfPresent(result, "footprint", part.getFootprint());
        putIfPresent(result, "datasheetUrl", part.getDatasheetUrl());
        putIfPresent(result, "category", part.getCategoryBreadcrumb() != null
                ? part.getCategoryBreadcrumb() : part.getCategoryName());
        result.put("categoryId", part.getCategoryId());
        result.put("tags", part.getTags() == null ? List.of() : part.getTags());
        result.put("totalQuantity", onHand);
        result.put("stock", stock);
        result.put("specs", specs);
        result.put("createdAt", part.getCreatedAt());
        result.put("updatedAt", part.getUpdatedAt());
        return result;
    }

    private Object listSpecFields(JsonNode args) {
        List<SpecDefinitionDTO> all = specDefinitionService.findAll();

        String filter = text(args, "filter");
        List<SpecDefinitionDTO> matching = filter == null ? all : all.stream()
                .filter(d -> contains(d.getJsonName(), filter) || contains(d.getName(), filter))
                .toList();

        int limit = bounded(args, "limit", DEFAULT_FIELD_LIMIT, MAX_FIELD_LIMIT);
        List<Map<String, Object>> rows = matching.stream()
                .limit(limit)
                .map(this::specFieldRow)
                .toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", matching.size());
        result.put("returned", rows.size());
        if (rows.size() < matching.size()) {
            result.put("note", "Showing " + rows.size() + " of " + matching.size()
                    + " fields; use `filter` to narrow, or raise `limit`.");
        }
        result.put("fields", rows);
        return result;
    }

    private Object listCategories() {
        Map<Long, Long> partCounts = new LinkedHashMap<>();
        collectCounts(categoryService.getTree(), partCounts);
        List<Map<String, Object>> rows = categoryService.findAll().stream()
                .sorted(Comparator.comparing(c -> breadcrumbOf(c).toLowerCase()))
                .map(c -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", c.getId());
                    row.put("name", c.getName());
                    row.put("breadcrumb", breadcrumbOf(c));
                    row.put("parentId", c.getParentId());
                    row.put("partCount", partCounts.getOrDefault(c.getId(), 0L));
                    return row;
                })
                .toList();
        return Map.of("total", rows.size(), "categories", rows);
    }

    private Object listLocations() {
        List<Map<String, Object>> rows = locationService.findAll().stream()
                .map(l -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", l.getId());
                    row.put("name", l.getName());
                    row.put("breadcrumb", l.getBreadcrumb() == null ? l.getName() : l.getBreadcrumb());
                    row.put("parentId", l.getParentId());
                    return row;
                })
                .toList();
        return Map.of("total", rows.size(), "locations", rows);
    }

    private Object listLowStock() {
        List<Map<String, Object>> rows = stockThresholdService.findLowStock().stream()
                .map(this::lowStockRow)
                .toList();
        return Map.of("total", rows.size(), "parts", rows);
    }

    // ---------------------------------------------------------------- row mapping

    private Map<String, Object> summarise(PartDTO part) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", part.getId());
        row.put("partNumber", part.getPartNumber());
        row.put("description", part.getDescription());
        putIfPresent(row, "manufacturer", part.getManufacturer());
        putIfPresent(row, "category", part.getCategoryBreadcrumb() != null
                ? part.getCategoryBreadcrumb() : part.getCategoryName());
        row.put("totalQuantity", part.getTotalQuantity());
        if (part.getTags() != null && !part.getTags().isEmpty()) {
            row.put("tags", part.getTags());
        }
        return row;
    }

    private Map<String, Object> stockRow(StockEntryDTO entry) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("location", entry.getLocationBreadcrumb() == null
                ? entry.getLocationName() : entry.getLocationBreadcrumb());
        row.put("locationId", entry.getLocationId());
        row.put("quantity", entry.getQuantity());
        if (entry.getUnitPrice() != null) {
            row.put("unitPrice", entry.getUnitPrice());
        }
        return row;
    }

    private Map<String, Object> specFieldRow(SpecDefinitionDTO definition) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("jsonName", definition.getJsonName());
        row.put("label", definition.getName());
        row.put("dataType", definition.getDataType());
        putIfPresent(row, "unit", definition.getUnit());
        putIfPresent(row, "unitFamily", definition.getUnitFamily());
        putIfPresent(row, "group", definition.getGroupName());
        if (definition.getOptions() != null && !definition.getOptions().isEmpty()) {
            row.put("options", definition.getOptions());
        }
        if (definition.getAliases() != null && !definition.getAliases().isEmpty()) {
            row.put("aliases", definition.getAliases());
        }
        return row;
    }

    private Map<String, Object> lowStockRow(StockThresholdDTO threshold) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("partId", threshold.getPartId());
        row.put("partNumber", threshold.getPartNumber());
        row.put("description", threshold.getPartName());
        putIfPresent(row, "location", threshold.getLocationName());
        row.put("onHand", threshold.getTotalQuantity());
        row.put("minimumQuantity", threshold.getMinimumQuantity());
        return row;
    }

    // ---------------------------------------------------------------- helpers

    /**
     * A part by its number. An exact match wins; otherwise the fuzzy matches are handed back as
     * candidates rather than one of them being picked — the wrong part looks exactly like the right
     * one in a tool result.
     */
    private PartDTO byPartNumber(String partNumber) {
        if (partNumber == null) {
            throw new ToolArgumentException("Give either `id` or `partNumber`.");
        }
        List<PartDTO> candidates = partService.fuzzyByPartNumber(partNumber);
        return candidates.stream()
                .filter(p -> partNumber.equalsIgnoreCase(p.getPartNumber()))
                .findFirst()
                .orElseThrow(() -> new ToolArgumentException(candidates.isEmpty()
                        ? "No part with part number '" + partNumber + "'."
                        : "No part is called exactly '" + partNumber + "'. Similar: "
                          + candidates.stream().limit(10).map(PartDTO::getPartNumber)
                                  .collect(Collectors.joining(", "))));
    }

    /**
     * Check the parametric criteria before running them. The search itself treats an unknown field
     * name as matching nothing — right for a UI, where the field came from a dropdown, but a trap
     * for a model, which cannot tell "you invented that field" from "you own no such part". Saying
     * which names exist is what lets it correct itself in one turn instead of concluding the
     * catalogue is empty.
     */
    private void validateSpecCriteria(List<String> criteria) {
        if (criteria.isEmpty()) return;
        Set<String> known = definitionsByJsonName().keySet();
        for (String raw : criteria) {
            String[] bits = raw.split(":", 3);
            if (bits.length < 2) {
                throw new ToolArgumentException("Malformed spec criterion '" + raw
                        + "'. Write it as \"<field>:<op>:<value>\", e.g. \"capacitance:eq:100nF\".");
            }
            String field = bits[0].trim();
            String op = bits[1].trim().toLowerCase();
            if (!known.contains(field)) {
                throw new ToolArgumentException("No spec field called '" + field + "'."
                        + suggest(known, field));
            }
            if (!SPEC_OPS.contains(op)) {
                throw new ToolArgumentException("'" + op + "' is not a spec operator. Use one of: "
                        + String.join(", ", SPEC_OPS.stream().sorted().toList()) + ".");
            }
        }
    }

    /** Field names close enough to the one asked for to be what was meant. */
    private String suggest(Set<String> known, String wanted) {
        String needle = wanted.toLowerCase();
        List<String> close = known.stream()
                .filter(name -> name.contains(needle) || needle.contains(name))
                .sorted()
                .limit(5)
                .toList();
        return close.isEmpty()
                ? " Call list_spec_fields for the field names."
                : " Did you mean: " + String.join(", ", close) + "?";
    }

    /** A category by name, since a model has a name in hand far more often than an id. */
    private Long resolveCategory(String name) {
        if (name == null) return null;
        List<CategoryDTO> all = categoryService.findAll();
        List<CategoryDTO> matches = all.stream()
                .filter(c -> name.equalsIgnoreCase(c.getName())
                        || name.equalsIgnoreCase(breadcrumbOf(c)))
                .toList();
        if (matches.size() == 1) {
            return matches.get(0).getId();
        }
        if (matches.isEmpty()) {
            throw new ToolArgumentException("No category called '" + name
                    + "'. Call list_categories for the tree.");
        }
        throw new ToolArgumentException("'" + name + "' matches several categories: "
                + matches.stream().map(this::breadcrumbOf).collect(Collectors.joining(", "))
                + ". Pass `categoryId` instead.");
    }

    private Map<String, SpecDefinition> definitionsByJsonName() {
        Map<String, SpecDefinition> byName = new LinkedHashMap<>();
        specDefinitionRepository
                .findByOrganisationIdOrderByDisplayOrderAscNameAsc(currentOrganisationService.currentId())
                .forEach(definition -> byName.putIfAbsent(definition.getJsonName(), definition));
        return byName;
    }

    private void collectCounts(List<CategoryTreeDTO> nodes, Map<Long, Long> into) {
        for (CategoryTreeDTO node : nodes) {
            into.put(node.getId(), node.getPartCount());
            if (node.getChildren() != null) {
                collectCounts(node.getChildren(), into);
            }
        }
    }

    private String breadcrumbOf(CategoryDTO category) {
        return category.getBreadcrumb() == null ? category.getName() : category.getBreadcrumb();
    }

    private JsonNode schema(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Malformed tool input schema", e);
        }
    }

    private static boolean contains(String haystack, String needle) {
        return haystack != null && haystack.toLowerCase().contains(needle.toLowerCase());
    }

    private static String text(JsonNode args, String field) {
        JsonNode value = args.get(field);
        if (value == null || value.isNull()) return null;
        String text = value.asText().trim();
        return text.isEmpty() ? null : text;
    }

    private static List<String> strings(JsonNode args, String field) {
        JsonNode value = args.get(field);
        if (value == null || value.isNull()) return List.of();
        Set<String> values = new LinkedHashSet<>();
        if (value.isArray()) {
            value.forEach(element -> {
                String text = element.asText().trim();
                if (!text.isEmpty()) values.add(text);
            });
        } else {
            String text = value.asText().trim();
            if (!text.isEmpty()) values.add(text);
        }
        return List.copyOf(values);
    }

    private static int bounded(JsonNode args, String field, int fallback, int max) {
        int requested = args.path(field).asInt(fallback);
        return Math.max(1, Math.min(max, requested));
    }

    private static void putIfPresent(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }
}
