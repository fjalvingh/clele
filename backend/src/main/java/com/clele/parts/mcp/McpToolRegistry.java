package com.clele.parts.mcp;

import com.clele.parts.dto.CategoryDTO;
import com.clele.parts.dto.CategoryTreeDTO;
import com.clele.parts.dto.LocationDTO;
import com.clele.parts.dto.PartDTO;
import com.clele.parts.dto.ProjectDTO;
import com.clele.parts.dto.ProjectPartDTO;
import com.clele.parts.dto.ProjectPartRequest;
import com.clele.parts.dto.SpecDefinitionDTO;
import com.clele.parts.dto.StockEntryDTO;
import com.clele.parts.dto.StockThresholdDTO;
import com.clele.parts.model.Permissions;
import com.clele.parts.model.ProjectStatus;
import com.clele.parts.model.SpecDefinition;
import com.clele.parts.repository.SpecDefinitionRepository;
import com.clele.parts.catalog.ComponentCacheService;
import com.clele.parts.dto.ComponentCacheMatchDTO;
import com.clele.parts.dto.FootprintDTO;
import com.clele.parts.dto.OrderItemDTO;
import com.clele.parts.dto.OrderItemRequest;
import com.clele.parts.dto.PartCreateRequest;
import com.clele.parts.service.CategoryService;
import com.clele.parts.service.FootprintService;
import com.clele.parts.service.order.OrderItemService;
import com.clele.parts.service.CurrentOrganisationService;
import com.clele.parts.service.LocationService;
import com.clele.parts.service.PartService;
import com.clele.parts.service.ProjectService;
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
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The tools the MCP endpoint offers, and what each one does.
 *
 * <p>Every tool goes through the ordinary services, so the organisation scoping, the parametric
 * spec search and the stock aggregate are the same code the web UI runs — an answer here cannot
 * drift from what the screen shows.
 *
 * <p>Four tools write, and nothing else does. {@code add_part} adds a part to the catalogue —
 * never changes or deletes one — for a new design's parts that are not owned yet; it insists on a
 * leaf category and a footprint from the managed list, because the AI is otherwise the fastest way
 * to fill both with plausible inventions. {@code add_order_item} puts a part on the order list.
 * {@code add_project_part} and {@code remove_project_part} move stock, because putting a part on
 * a project's list takes it off the shelf there and then. All four are gated on
 * {@link Permissions#PARTS_EDIT} in the organisation the credential was issued for — the same
 * permission the controllers demand of a browser — because a tool here calls the service directly
 * and so never passes a controller's {@code @PreAuthorize}.
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
    private static final int DEFAULT_PROJECT_LIMIT = 50;
    private static final int MAX_PROJECT_LIMIT = 200;
    private static final int SIMILAR_PARTS = 40;
    private static final int MAX_CATEGORY_SUGGESTIONS = 5;

    private final ObjectMapper objectMapper;
    private final PartService partService;
    private final StockEntryService stockEntryService;
    private final StockThresholdService stockThresholdService;
    private final SpecDefinitionService specDefinitionService;
    private final SpecDefinitionRepository specDefinitionRepository;
    private final CategoryService categoryService;
    private final LocationService locationService;
    private final ProjectService projectService;
    private final CurrentOrganisationService currentOrganisationService;
    private final FootprintService footprintService;
    private final OrderItemService orderItemService;
    private final ComponentCacheService componentCacheService;

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
                                   "footprint": {"type": "string", "description": "Package, a name or alias from list_footprints (SOT-23, DIP-28W, 0805). Matched exactly, ignoring case and punctuation."},
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
                        The category tree, flattened, each with its full breadcrumb, its \
                        description and how many parts sit in it. Categories nest, and a search by \
                        category includes everything below it. `leaf` marks a category with no \
                        children — the only kind a part can be added to.""",
                        schema("""
                               {
                                 "type": "object",
                                 "properties": {
                                   "leavesOnly": {"type": "boolean", "description": "Only the leaf categories. Default false."}
                                 }
                               }
                               """),
                        this::listCategories),

                new Tool("suggest_category", "Suggest a category for a new part",
                        """
                        Where a part not yet in the catalogue belongs. Ranks the leaf categories by \
                        where the most similar existing parts were filed (so it follows this \
                        catalogue's own conventions, not a generic taxonomy), and, given an `mpn`, \
                        also reports what the local distributor snapshot knows of it: its \
                        distributor category, description, manufacturer and package (with the \
                        footprint name that package maps to here). Call it before add_part. The \
                        suggestions are evidence, not a verdict: when none fits, pick a leaf from \
                        list_categories(leavesOnly).""",
                        schema("""
                               {
                                 "type": "object",
                                 "properties": {
                                   "description": {"type": "string", "description": "What the part is, in words, e.g. \\"LDO voltage regulator 3.3V 500mA\\"."},
                                   "mpn": {"type": "string", "description": "The manufacturer part number, if known."}
                                 },
                                 "required": ["description"]
                               }
                               """),
                        this::suggestCategory),

                new Tool("list_footprints", "List footprints",
                        """
                        The footprints (package types) parts may have, each with the other names it \
                        is known by and how many parts use it. add_part only accepts a name or \
                        alias from this list. Matching ignores case and punctuation, so SOT23 is \
                        SOT-23; an alias covers what that cannot (TO-236 is SOT-23, \
                        "0805 (2012 Metric)" is 0805). Chip sizes are imperial.""",
                        schema("""
                               {
                                 "type": "object",
                                 "properties": {
                                   "query": {"type": "string", "description": "Only footprints whose name or an alias contains this, ignoring case and punctuation."}
                                 }
                               }
                               """),
                        this::listFootprints),

                new Tool("list_locations", "List storage locations",
                        """
                        The storage locations stock can be held in, flattened from their tree, each \
                        with its full breadcrumb. A drawer divided into slots (one kind of part per \
                        slot) also carries slotCount, usedSlots and freeSlots.""",
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
                        args -> listLowStock()),

                new Tool("search_projects", "Search projects",
                        """
                        The projects belonging to whoever this credential acts as — projects are \
                        private to their owner, so this never shows anyone else's. A project is a \
                        build with a parts list; while it is ACTIVE the parts on that list are out \
                        of stock and held by the project, and while it is CANCELLED they have all \
                        been given back. `anyShortfall` marks a project some line of which holds \
                        less than the build needs. Use get_project for the parts list itself.""",
                        schema("""
                               {
                                 "type": "object",
                                 "properties": {
                                   "query": {"type": "string", "description": "Case-insensitive substring of the project name or description."},
                                   "status": {"type": "string", "description": "ACTIVE or CANCELLED. Both when absent."},
                                   "limit": {"type": "integer", "description": "Max projects to return, 1-200. Default 50."}
                                 }
                               }
                               """),
                        this::searchProjects),

                new Tool("get_project", "Get a project and its parts list",
                        """
                        One project in full, with every line of its parts list: the part, how many \
                        one build instance needs (`qtyPerInstance`), the whole-build need \
                        (`totalNeeded` = qtyPerInstance × instanceCount), how many the project is \
                        actually holding out of stock (`qtyAllocated`) and the `shortfall` stock \
                        could not supply. Identify the project by `projectId` (from \
                        search_projects) or by its exact `project` name.""",
                        schema("""
                               {
                                 "type": "object",
                                 "properties": {
                                   "projectId": {"type": "integer", "description": "The project id, as returned by search_projects."},
                                   "project": {"type": "string", "description": "Exact project name; used when `projectId` is absent."}
                                 }
                               }
                               """),
                        this::getProject),

                new Tool("add_project_part", "Add a part to a project's parts list",
                        """
                        Put a part on a project's parts list. THIS MOVES STOCK: the parts the build \
                        needs are taken off the shelf and held by the project the moment the line \
                        is added — there is no state in which a project lists a part but has not \
                        taken it. Running short is a state, not a failure: if the shelf cannot \
                        cover the need the line is still added, holding what there was, and the \
                        result reports the shortfall. Identify the project by `projectId` or \
                        `project`, the part by `partId` or `partNumber`. The project must be \
                        ACTIVE, the part must not already be on the list, and the credential needs \
                        the PARTS_EDIT permission.""",
                        schema("""
                               {
                                 "type": "object",
                                 "properties": {
                                   "projectId": {"type": "integer", "description": "The project id, as returned by search_projects."},
                                   "project": {"type": "string", "description": "Exact project name; used when `projectId` is absent."},
                                   "partId": {"type": "integer", "description": "The part id, as returned by search_parts."},
                                   "partNumber": {"type": "string", "description": "Exact part number; used when `partId` is absent."},
                                   "qtyPerInstance": {"type": "integer", "description": "How many one build instance needs. At least 1; default 1."},
                                   "notes": {"type": "string", "description": "A note against this line, e.g. the reference designators it covers."}
                                 }
                               }
                               """),
                        this::addProjectPart),

                new Tool("remove_project_part", "Remove a part from a project's parts list",
                        """
                        Take a part off a project's parts list. THIS MOVES STOCK: everything the \
                        project was holding of it goes back to the locations it was drawn from. \
                        The whole line goes, need and all — to hand parts back while keeping the \
                        line (so it reads as short until they are fetched again) is a different \
                        thing, and this is not it. Identify the project by `projectId` or \
                        `project`, the part by `partId` or `partNumber`. The project must be \
                        ACTIVE and the credential needs the PARTS_EDIT permission.""",
                        schema("""
                               {
                                 "type": "object",
                                 "properties": {
                                   "projectId": {"type": "integer", "description": "The project id, as returned by search_projects."},
                                   "project": {"type": "string", "description": "Exact project name; used when `projectId` is absent."},
                                   "partId": {"type": "integer", "description": "The part id, as returned by search_parts or get_project."},
                                   "partNumber": {"type": "string", "description": "Exact part number; used when `partId` is absent."}
                                 }
                               }
                               """),
                        this::removeProjectPart),

                new Tool("add_part", "Add a part to the catalogue",
                        """
                        Add a new part to the catalogue, with no stock — typically a part a new \
                        design needs and that has to be ordered. Required: `mpn` (it becomes the \
                        part number), `manufacturer`, `description`, a LEAF category \
                        (`categoryId`, or `category` by name/breadcrumb) and a `footprint` from \
                        list_footprints. Before calling: search_parts to make sure the part (or an \
                        equivalent) is not already owned, and suggest_category for the category. \
                        A footprint the list does not know is refused with the closest names; \
                        only when the part truly has a package nobody uses yet, call again with \
                        `newFootprint: true` to add it to the list. An existing part number is \
                        refused. Afterwards use add_project_part and/or add_order_item. Needs \
                        the PARTS_EDIT permission.""",
                        schema("""
                               {
                                 "type": "object",
                                 "properties": {
                                   "mpn": {"type": "string", "description": "Manufacturer part number, exactly as the manufacturer writes it; becomes the part number."},
                                   "manufacturer": {"type": "string", "description": "The manufacturer, e.g. \\"Texas Instruments\\"."},
                                   "description": {"type": "string", "description": "A short engineer's description, e.g. \\"LDO regulator 3.3V 500mA\\"."},
                                   "categoryId": {"type": "integer", "description": "A leaf category id, from suggest_category or list_categories."},
                                   "category": {"type": "string", "description": "A leaf category's exact name or breadcrumb; used when `categoryId` is absent."},
                                   "footprint": {"type": "string", "description": "The package, a name or alias from list_footprints, e.g. \\"SOT-23-5\\"."},
                                   "newFootprint": {"type": "boolean", "description": "Add `footprint` to the list when it is not on it. Only after list_footprints showed nothing that fits. Default false."},
                                   "details": {"type": "string", "description": "Longer notes about the part."},
                                   "datasheetUrl": {"type": "string", "description": "A link to the datasheet."},
                                   "tags": {"type": "array", "items": {"type": "string"}, "description": "Tags to put on the part."}
                                 },
                                 "required": ["mpn", "manufacturer", "description", "footprint"]
                               }
                               """),
                        this::addPart),

                new Tool("add_order_item", "Put a part on the order list",
                        """
                        Put a part on the order list: "we need N of this", optionally for one of \
                        your projects. This orders nothing — the list is what the user later turns \
                        into an order at a supplier — and it moves no stock. Without a \
                        `unitPrice` the last price any supplier charged is used. Identify the part \
                        by `partId` or `partNumber`, the project (optional) by `projectId` or \
                        `project`. Needs the PARTS_EDIT permission.""",
                        schema("""
                               {
                                 "type": "object",
                                 "properties": {
                                   "partId": {"type": "integer", "description": "The part id, as returned by search_parts or add_part."},
                                   "partNumber": {"type": "string", "description": "Exact part number; used when `partId` is absent."},
                                   "quantity": {"type": "integer", "description": "How many to order. At least 1."},
                                   "projectId": {"type": "integer", "description": "The project these are for, as returned by search_projects."},
                                   "project": {"type": "string", "description": "Exact project name; used when `projectId` is absent."},
                                   "unitPrice": {"type": "number", "description": "Expected price per piece."},
                                   "notes": {"type": "string", "description": "A note against the item."}
                                 },
                                 "required": ["quantity"]
                               }
                               """),
                        this::addOrderItem));
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
                null, text(args, "manufacturer"), text(args, "footprint"), null, null,
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

    private Object listCategories(JsonNode args) {
        boolean leavesOnly = args.path("leavesOnly").asBoolean(false);
        Map<Long, Long> partCounts = new LinkedHashMap<>();
        collectCounts(categoryService.getTree(), partCounts);
        List<CategoryDTO> all = categoryService.findAll();
        Set<Long> parents = parentIds(all);
        List<Map<String, Object>> rows = all.stream()
                .filter(c -> !leavesOnly || !parents.contains(c.getId()))
                .sorted(Comparator.comparing(c -> breadcrumbOf(c).toLowerCase()))
                .map(c -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", c.getId());
                    row.put("name", c.getName());
                    row.put("breadcrumb", breadcrumbOf(c));
                    putIfPresent(row, "description", c.getDescription());
                    row.put("parentId", c.getParentId());
                    row.put("leaf", !parents.contains(c.getId()));
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
                    if (l.getSlotCount() != null) {
                        row.put("slotCount", l.getSlotCount());
                        row.put("usedSlots", l.getUsedSlots());
                        row.put("freeSlots", l.getFreeSlots());
                    }
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

    private Object searchProjects(JsonNode args) {
        String query = text(args, "query");
        ProjectStatus status = parseStatus(text(args, "status"));

        List<ProjectDTO> found = projectService.findAll().stream()
                .filter(p -> query == null
                        || contains(p.getName(), query) || contains(p.getDescription(), query))
                .filter(p -> status == null || p.getStatus() == status)
                .toList();

        int limit = bounded(args, "limit", DEFAULT_PROJECT_LIMIT, MAX_PROJECT_LIMIT);
        List<Map<String, Object>> rows = found.stream()
                .limit(limit)
                .map(this::projectRow)
                .toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", found.size());
        result.put("returned", rows.size());
        if (rows.size() < found.size()) {
            result.put("note", "Showing the first " + rows.size() + " of " + found.size()
                    + " projects; narrow with `query` or raise `limit` (max "
                    + MAX_PROJECT_LIMIT + ").");
        }
        result.put("projects", rows);
        return result;
    }

    private Object getProject(JsonNode args) {
        return projectDetail(resolveProject(args));
    }

    private Object addProjectPart(JsonNode args) {
        requireWriteAccess("changing a project's parts list moves stock and");
        ProjectDTO project = resolveProject(args);
        PartDTO part = resolvePart(args);

        int qtyPerInstance = args.path("qtyPerInstance").asInt(1);
        if (qtyPerInstance < 1) {
            throw new ToolArgumentException("`qtyPerInstance` must be at least 1.");
        }

        ProjectPartRequest request = new ProjectPartRequest();
        request.setPartId(part.getId());
        request.setQtyPerInstance(qtyPerInstance);
        request.setNotes(text(args, "notes"));
        ProjectPartDTO line = projectService.addPart(project.getId(), request);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("projectId", project.getId());
        result.put("project", project.getName());
        result.put("added", projectPartRow(line));
        result.put("takenFromStock", line.getQtyAllocated());
        if (line.getShortfall() > 0) {
            result.put("note", "Stock could only supply " + line.getQtyAllocated() + " of the "
                    + line.getTotalNeeded() + " this build needs; the line is on the list "
                    + line.getShortfall() + " short.");
        }
        return result;
    }

    private Object removeProjectPart(JsonNode args) {
        requireWriteAccess("changing a project's parts list moves stock and");
        ProjectDTO project = resolveProject(args);
        PartDTO part = resolvePart(args);
        ProjectPartDTO line = lineFor(project, part);

        int held = line.getQtyAllocated();
        projectService.removePart(project.getId(), line.getId());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("projectId", project.getId());
        result.put("project", project.getName());
        result.put("removed", projectPartRow(line));
        result.put("returnedToStock", held);
        return result;
    }

    /**
     * Rank categories by where the parts most like the description were filed. The rank of each
     * similar part counts, not just its presence, so three close neighbours outweigh ten that
     * share one common word.
     */
    private Object suggestCategory(JsonNode args) {
        String description = text(args, "description");
        if (description == null) {
            throw new ToolArgumentException("Give a `description` of the part.");
        }
        String mpn = text(args, "mpn");

        Map<String, Object> result = new LinkedHashMap<>();
        StringBuilder words = new StringBuilder(description);
        ComponentCacheMatchDTO cached = mpn == null ? null : componentCacheService.search(mpn).stream()
                .filter(m -> mpn.equalsIgnoreCase(m.getMpn()))
                .findFirst()
                .orElse(null);
        if (cached != null) {
            Map<String, Object> known = new LinkedHashMap<>();
            known.put("mpn", cached.getMpn());
            putIfPresent(known, "manufacturer", cached.getManufacturer());
            putIfPresent(known, "description", cached.getDescription());
            putIfPresent(known, "distributorCategory", cached.getSubcategory() == null
                    ? cached.getCategory() : cached.getCategory() + " / " + cached.getSubcategory());
            if (cached.getPackageName() != null) {
                known.put("package", cached.getPackageName());
                FootprintService.Match footprint = footprintService.match(cached.getPackageName());
                if (footprint.found()) {
                    known.put("footprint", footprint.name());
                } else if (!footprint.similar().isEmpty()) {
                    known.put("footprintCandidates", footprint.similar());
                }
            }
            result.put("distributorSnapshot", known);
            words.append(' ').append(Objects.toString(cached.getDescription(), ""))
                    .append(' ').append(Objects.toString(cached.getSubcategory(), ""));
        }

        List<CategoryDTO> all = categoryService.findAll();
        Set<Long> parents = parentIds(all);
        Map<Long, CategoryDTO> byId = all.stream().collect(Collectors.toMap(CategoryDTO::getId, c -> c));

        Map<Long, Double> score = new LinkedHashMap<>();
        Map<Long, List<PartDTO>> examples = new LinkedHashMap<>();
        List<PartDTO> similar = partService.mostSimilar(words.toString(), SIMILAR_PARTS);
        for (int i = 0; i < similar.size(); i++) {
            PartDTO part = similar.get(i);
            if (part.getCategoryId() == null) continue;
            score.merge(part.getCategoryId(), 1.0 / Math.sqrt(i + 1), Double::sum);
            examples.computeIfAbsent(part.getCategoryId(), k -> new ArrayList<>()).add(part);
        }
        double total = score.values().stream().mapToDouble(Double::doubleValue).sum();

        List<Map<String, Object>> suggestions = score.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
                .limit(MAX_CATEGORY_SUGGESTIONS)
                .map(e -> {
                    CategoryDTO category = byId.get(e.getKey());
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", e.getKey());
                    row.put("breadcrumb", category == null ? null : breadcrumbOf(category));
                    if (category != null) putIfPresent(row, "description", category.getDescription());
                    row.put("leaf", !parents.contains(e.getKey()));
                    row.put("confidence", Math.round(100 * e.getValue() / total) / 100.0);
                    row.put("similarParts", examples.get(e.getKey()).stream()
                            .limit(3)
                            .map(p -> p.getPartNumber() + (p.getDescription() == null ? "" : " — " + p.getDescription()))
                            .toList());
                    return row;
                })
                .toList();
        result.put("suggestions", suggestions);
        result.put("note", suggestions.isEmpty()
                ? "No similar parts in the catalogue to learn from; pick a leaf from list_categories(leavesOnly: true)."
                : "Ranked by where the most similar parts in this catalogue were filed. A part can only be "
                  + "added to a leaf; if the best fit is not one, or none fits, see list_categories(leavesOnly: true).");
        return result;
    }

    private Object listFootprints(JsonNode args) {
        String query = text(args, "query");
        String key = query == null ? null : FootprintService.matchKey(query);
        List<Map<String, Object>> rows = footprintService.findAll().stream()
                .filter(f -> key == null
                        || FootprintService.matchKey(f.getName()).contains(key)
                        || f.getAliases().stream().anyMatch(a -> FootprintService.matchKey(a).contains(key)))
                .sorted(Comparator.comparingLong(FootprintDTO::getPartCount).reversed()
                        .thenComparing(f -> f.getName().toLowerCase(Locale.ROOT)))
                .map(f -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("name", f.getName());
                    if (!f.getAliases().isEmpty()) row.put("aliases", f.getAliases());
                    putIfPresent(row, "description", f.getDescription());
                    row.put("partCount", f.getPartCount());
                    return row;
                })
                .toList();
        return Map.of("total", rows.size(), "footprints", rows);
    }

    private Object addPart(JsonNode args) {
        requireWriteAccess("adding a part to the catalogue");
        String mpn = required(args, "mpn");
        String manufacturer = required(args, "manufacturer");
        String description = required(args, "description");
        String footprintGiven = required(args, "footprint");

        Long categoryId = args.hasNonNull("categoryId")
                ? args.get("categoryId").asLong()
                : resolveCategory(text(args, "category"));
        if (categoryId == null) {
            throw new ToolArgumentException("Give a leaf category as `categoryId` or `category`; "
                    + "suggest_category proposes one.");
        }
        requireLeaf(categoryId);

        List<PartDTO> close = partService.fuzzyByPartNumber(mpn);
        close.stream()
                .filter(p -> mpn.equalsIgnoreCase(p.getPartNumber()))
                .findFirst()
                .ifPresent(p -> {
                    throw new ToolArgumentException("Part " + p.getPartNumber() + " is already in the "
                            + "catalogue (id " + p.getId() + "). Use it instead of adding another.");
                });

        if (!FootprintService.isPackageName(footprintGiven)) {
            throw new ToolArgumentException("'" + footprintGiven + "' is a mounting style, not a "
                    + "package. Give the package itself, such as SOT-23, 0805, DIP-8 or TO-220 "
                    + "(see list_footprints).");
        }
        FootprintService.Match footprint = footprintService.match(footprintGiven);
        boolean newFootprint = false;
        if (!footprint.found()) {
            if (!args.path("newFootprint").asBoolean(false)) {
                throw new ToolArgumentException("'" + footprintGiven + "' is not on the footprint list."
                        + (footprint.similar().isEmpty()
                                ? " Nothing on it looks similar."
                                : " Closest: " + String.join(", ", footprint.similar()) + ".")
                        + " Use one of those (see list_footprints), or, only if the part really has a "
                        + "package nobody uses yet, call again with `newFootprint: true`.");
            }
            newFootprint = true;
        }

        PartCreateRequest request = new PartCreateRequest();
        request.setPartNumber(mpn);
        request.setManufacturer(manufacturer);
        request.setDescription(description);
        request.setDetails(text(args, "details"));
        request.setDatasheetUrl(text(args, "datasheetUrl"));
        request.setFootprint(footprint.found() ? footprint.name() : footprintGiven);
        request.setCategoryId(categoryId);
        List<String> tags = strings(args, "tags");
        if (!tags.isEmpty()) request.setTags(tags);
        PartDTO part = partService.create(request);

        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Object> added = summarise(part);
        added.put("totalQuantity", 0);  // a new part has no stock; the DTO leaves it null
        result.put("added", added);
        result.put("footprint", part.getFootprint());
        if (newFootprint) {
            result.put("footprintAdded", true);
        } else if (!footprintGiven.equals(part.getFootprint())) {
            result.put("footprintNote", "'" + footprintGiven + "' is stored as " + part.getFootprint() + ".");
        }
        List<String> nearby = close.stream()
                .map(PartDTO::getPartNumber)
                .filter(n -> !n.equalsIgnoreCase(mpn))
                .limit(5)
                .toList();
        if (!nearby.isEmpty()) {
            result.put("similarPartNumbers", nearby);
            result.put("note", "The catalogue also holds parts with similar numbers: "
                    + String.join(", ", nearby) + ". If one of them is this part, say so to the user.");
        }
        return result;
    }

    private Object addOrderItem(JsonNode args) {
        requireWriteAccess("putting a part on the order list");
        PartDTO part = resolvePart(args);
        int quantity = args.path("quantity").asInt(0);
        if (quantity < 1) {
            throw new ToolArgumentException("`quantity` must be at least 1.");
        }
        ProjectDTO project = args.hasNonNull("projectId") || text(args, "project") != null
                ? resolveProject(args)
                : null;

        OrderItemRequest request = new OrderItemRequest();
        request.setPartId(part.getId());
        request.setQuantity(quantity);
        request.setProjectId(project == null ? null : project.getId());
        if (args.hasNonNull("unitPrice")) {
            BigDecimal price = args.get("unitPrice").decimalValue();
            if (price.signum() < 0) {
                throw new ToolArgumentException("`unitPrice` cannot be negative.");
            }
            request.setUnitPrice(price);
        }
        request.setNotes(text(args, "notes"));
        OrderItemDTO item = orderItemService.create(request);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("orderItemId", item.getId());
        result.put("partId", part.getId());
        result.put("partNumber", part.getPartNumber());
        result.put("quantity", item.getQuantity());
        if (project != null) result.put("project", project.getName());
        if (item.getUnitPrice() != null) result.put("unitPrice", item.getUnitPrice());
        return result;
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

    private Map<String, Object> projectRow(ProjectDTO project) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", project.getId());
        row.put("name", project.getName());
        putIfPresent(row, "description", project.getDescription());
        row.put("status", project.getStatus());
        row.put("instanceCount", project.getInstanceCount());
        row.put("partCount", project.getPartCount());
        row.put("anyShortfall", project.isAnyShortfall());
        row.put("updatedAt", project.getUpdatedAt());
        return row;
    }

    private Map<String, Object> projectDetail(ProjectDTO project) {
        Map<String, Object> result = projectRow(project);
        result.put("createdAt", project.getCreatedAt());
        if (project.getTotalStockValue() != null) {
            result.put("totalStockValue", project.getTotalStockValue());
        }
        List<ProjectPartDTO> parts = project.getParts() == null ? List.of() : project.getParts();
        result.put("parts", parts.stream().map(this::projectPartRow).toList());
        return result;
    }

    private Map<String, Object> projectPartRow(ProjectPartDTO line) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("projectPartId", line.getId());
        row.put("partId", line.getPartId());
        row.put("partNumber", line.getPartNumber());
        row.put("description", line.getPartName());
        row.put("qtyPerInstance", line.getQtyPerInstance());
        row.put("totalNeeded", line.getTotalNeeded());
        row.put("qtyAllocated", line.getQtyAllocated());
        row.put("shortfall", line.getShortfall());
        putIfPresent(row, "notes", line.getNotes());
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
     * The project a tool was pointed at, as a detail DTO — {@code projectService.findById} scopes it
     * to the organisation <em>and</em> to the owner, since projects are private. A name is resolved
     * here because a model has one in hand far more often than an id.
     */
    private ProjectDTO resolveProject(JsonNode args) {
        if (args.hasNonNull("projectId")) {
            long id = args.get("projectId").asLong();
            try {
                return projectService.findById(id);
            } catch (ResponseStatusException e) {
                if (e.getStatusCode() != HttpStatus.NOT_FOUND) throw e;
                throw new ToolArgumentException("No project with id " + id
                        + " belongs to you. Call search_projects for the list.");
            }
        }

        String name = text(args, "project");
        if (name == null) {
            throw new ToolArgumentException("Give either `projectId` or `project` (its name).");
        }
        List<ProjectDTO> all = projectService.findAll();
        List<ProjectDTO> exact = all.stream()
                .filter(p -> name.equalsIgnoreCase(p.getName()))
                .toList();
        if (exact.size() == 1) {
            return projectService.findById(exact.get(0).getId());
        }
        if (exact.isEmpty()) {
            List<String> close = all.stream()
                    .filter(p -> contains(p.getName(), name))
                    .map(ProjectDTO::getName)
                    .limit(5)
                    .toList();
            throw new ToolArgumentException("No project called '" + name + "'."
                    + (close.isEmpty()
                            ? " Call search_projects for the list."
                            : " Did you mean: " + String.join(", ", close) + "?"));
        }
        throw new ToolArgumentException("'" + name + "' matches several projects (ids "
                + exact.stream().map(p -> String.valueOf(p.getId()))
                        .collect(Collectors.joining(", "))
                + "). Pass `projectId` instead.");
    }

    /** The part a project tool was pointed at, by id or by exact part number. */
    private PartDTO resolvePart(JsonNode args) {
        if (args.hasNonNull("partId")) {
            return partService.findById(args.get("partId").asLong());
        }
        String partNumber = text(args, "partNumber");
        if (partNumber == null) {
            throw new ToolArgumentException("Give either `partId` or `partNumber`.");
        }
        return byPartNumber(partNumber);
    }

    /** The line a project's parts list holds for one part, or a failure naming what is on it. */
    private ProjectPartDTO lineFor(ProjectDTO project, PartDTO part) {
        List<ProjectPartDTO> parts = project.getParts() == null ? List.of() : project.getParts();
        return parts.stream()
                .filter(line -> part.getId().equals(line.getPartId()))
                .findFirst()
                .orElseThrow(() -> new ToolArgumentException("Project '" + project.getName()
                        + "' has no parts list line for " + part.getPartNumber()
                        + (parts.isEmpty()
                                ? "; its parts list is empty."
                                : ". It lists: " + parts.stream().map(ProjectPartDTO::getPartNumber)
                                        .limit(20).collect(Collectors.joining(", ")) + ".")));
    }

    private ProjectStatus parseStatus(String status) {
        if (status == null) return null;
        return Arrays.stream(ProjectStatus.values())
                .filter(value -> value.name().equalsIgnoreCase(status))
                .findFirst()
                .orElseThrow(() -> new ToolArgumentException("'" + status
                        + "' is not a project status. Use one of: "
                        + Arrays.stream(ProjectStatus.values()).map(Enum::name)
                                .collect(Collectors.joining(", ")) + "."));
    }

    /**
     * The gate on the two tools that write. Every other tool reads, and a credential that may read
     * the catalogue must not be talked into moving stock — so the permission a browser needs for
     * the same operation is demanded here too. It is checked in this class rather than left to
     * {@code ProjectController}'s {@code @PreAuthorize} because these tools call the service
     * directly and never go through that controller.
     */
    private void requireWriteAccess(String what) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean permitted = auth != null && auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(Permissions.PARTS_EDIT::equals);
        if (!permitted) {
            throw new ToolArgumentException("This credential may read but not write: " + what
                    + " needs the " + Permissions.PARTS_EDIT + " permission in this organisation.");
        }
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

    /** The ids that are some category's parent — everything else is a leaf. */
    private static Set<Long> parentIds(List<CategoryDTO> categories) {
        Set<Long> parents = new HashSet<>();
        categories.forEach(c -> {
            if (c.getParentId() != null) parents.add(c.getParentId());
        });
        return parents;
    }

    /**
     * Only a leaf may hold a new part, the same rule the local auto-categorizer keeps: a part filed
     * under "Semiconductors" is one nobody finds by browsing. The refusal names the leaves below,
     * so the next call can pick one.
     */
    private void requireLeaf(Long categoryId) {
        List<CategoryDTO> all = categoryService.findAll();
        CategoryDTO category = all.stream()
                .filter(c -> c.getId().equals(categoryId))
                .findFirst()
                .orElseThrow(() -> new ToolArgumentException("No category with id " + categoryId
                        + ". Call list_categories for the tree."));
        Set<Long> parents = parentIds(all);
        if (!parents.contains(categoryId)) return;

        String prefix = breadcrumbOf(category) + " > ";
        List<String> leaves = all.stream()
                .filter(c -> !parents.contains(c.getId()) && breadcrumbOf(c).startsWith(prefix))
                .map(c -> breadcrumbOf(c) + " (id " + c.getId() + ")")
                .sorted()
                .limit(30)
                .toList();
        throw new ToolArgumentException("'" + breadcrumbOf(category) + "' has sub-categories; a part "
                + "goes in a leaf. Below it: " + String.join("; ", leaves) + ".");
    }

    private static String required(JsonNode args, String field) {
        String value = text(args, field);
        if (value == null) {
            throw new ToolArgumentException("`" + field + "` is required.");
        }
        return value;
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
