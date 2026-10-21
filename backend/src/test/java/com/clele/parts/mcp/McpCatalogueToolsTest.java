package com.clele.parts.mcp;

import com.clele.parts.catalog.ComponentCacheService;
import com.clele.parts.dto.CategoryDTO;
import com.clele.parts.dto.OrderItemDTO;
import com.clele.parts.dto.OrderItemRequest;
import com.clele.parts.dto.PartCreateRequest;
import com.clele.parts.dto.PartDTO;
import com.clele.parts.model.Permissions;
import com.clele.parts.repository.SpecDefinitionRepository;
import com.clele.parts.service.CategoryService;
import com.clele.parts.service.CurrentOrganisationService;
import com.clele.parts.service.FootprintService;
import com.clele.parts.service.LocationService;
import com.clele.parts.service.PartService;
import com.clele.parts.service.ProjectService;
import com.clele.parts.service.SpecDefinitionService;
import com.clele.parts.service.StockEntryService;
import com.clele.parts.service.StockThresholdService;
import com.clele.parts.service.order.OrderItemService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The tools that add to the catalogue and the order list. What is pinned is what keeps a model
 * from filling the catalogue with plausible inventions: a part goes into a leaf category, its
 * footprint comes from the managed list unless the call says outright that it is new, and an
 * existing part number is never added twice.
 */
class McpCatalogueToolsTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PartService partService = mock(PartService.class);
    private final CategoryService categoryService = mock(CategoryService.class);
    private final FootprintService footprintService = mock(FootprintService.class);
    private final OrderItemService orderItemService = mock(OrderItemService.class);

    private final McpToolRegistry registry = new McpToolRegistry(
            objectMapper,
            partService,
            mock(StockEntryService.class),
            mock(StockThresholdService.class),
            mock(SpecDefinitionService.class),
            mock(SpecDefinitionRepository.class),
            categoryService,
            mock(LocationService.class),
            mock(ProjectService.class),
            mock(CurrentOrganisationService.class),
            footprintService,
            orderItemService,
            mock(ComponentCacheService.class));

    @BeforeEach
    void categories() {
        when(categoryService.findAll()).thenReturn(List.of(
                category(270L, "Power", null),
                category(271L, "LDO", 270L),
                category(272L, "DC-DC", 270L)));
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void addPartWithoutPartsEditIsRefusedBeforeAnythingIsCreated() {
        authenticateWith();

        ObjectNode raw = registry.call("add_part", validPart());

        assertThat(raw.get("isError").asBoolean()).isTrue();
        assertThat(payload(raw).get("error").asText()).contains(Permissions.PARTS_EDIT);
        verify(partService, never()).create(any());
    }

    @Test
    void addPartStoresTheCanonicalFootprintAndSaysSo() {
        authenticateWith(Permissions.PARTS_EDIT);
        when(footprintService.match("SOT-753")).thenReturn(new FootprintService.Match("SOT-23-5", List.of()));
        when(partService.create(any())).thenAnswer(inv -> {
            PartCreateRequest r = inv.getArgument(0);
            PartDTO part = part(16L, r.getPartNumber());
            part.setFootprint(r.getFootprint());
            return part;
        });

        JsonNode result = call("add_part", validPart());

        ArgumentCaptor<PartCreateRequest> request = ArgumentCaptor.forClass(PartCreateRequest.class);
        verify(partService).create(request.capture());
        assertThat(request.getValue().getPartNumber()).isEqualTo("AP2112K-3.3TRG1");
        assertThat(request.getValue().getCategoryId()).isEqualTo(271L);
        assertThat(request.getValue().getFootprint()).isEqualTo("SOT-23-5");
        assertThat(request.getValue().getQuantity()).isNull();
        assertThat(result.get("footprint").asText()).isEqualTo("SOT-23-5");
        assertThat(result.get("footprintNote").asText()).contains("SOT-753");
    }

    @Test
    void anUnknownFootprintIsRefusedWithTheClosestNames() {
        authenticateWith(Permissions.PARTS_EDIT);
        when(footprintService.match("SOT23-5L"))
                .thenReturn(new FootprintService.Match(null, List.of("SOT-23-5", "SOT-23")));

        ObjectNode args = validPart();
        args.put("footprint", "SOT23-5L");
        ObjectNode raw = registry.call("add_part", args);

        assertThat(raw.get("isError").asBoolean()).isTrue();
        assertThat(payload(raw).get("error").asText())
                .contains("SOT-23-5")
                .contains("newFootprint");
        verify(partService, never()).create(any());
    }

    @Test
    void anUnknownFootprintIsAcceptedWhenTheCallSaysItIsNew() {
        authenticateWith(Permissions.PARTS_EDIT);
        when(footprintService.match("HTSSOP-14")).thenReturn(new FootprintService.Match(null, List.of()));
        when(partService.create(any())).thenAnswer(inv -> {
            PartDTO part = part(17L, "X");
            part.setFootprint("HTSSOP-14");
            return part;
        });

        ObjectNode args = validPart();
        args.put("footprint", "HTSSOP-14");
        args.put("newFootprint", true);
        JsonNode result = call("add_part", args);

        assertThat(result.get("footprintAdded").asBoolean()).isTrue();
    }

    @Test
    void aMountingStyleIsRefusedEvenWithNewFootprint() {
        authenticateWith(Permissions.PARTS_EDIT);

        ObjectNode args = validPart();
        args.put("footprint", "SMD");
        args.put("newFootprint", true);
        ObjectNode raw = registry.call("add_part", args);

        assertThat(raw.get("isError").asBoolean()).isTrue();
        assertThat(payload(raw).get("error").asText()).contains("not a package");
        verify(partService, never()).create(any());
    }

    @Test
    void aCategoryWithChildrenIsRefusedNamingTheLeavesBelowIt() {
        authenticateWith(Permissions.PARTS_EDIT);

        ObjectNode args = validPart();
        args.put("categoryId", 270);
        ObjectNode raw = registry.call("add_part", args);

        assertThat(raw.get("isError").asBoolean()).isTrue();
        assertThat(payload(raw).get("error").asText())
                .contains("Power > LDO (id 271)")
                .contains("Power > DC-DC (id 272)");
        verify(partService, never()).create(any());
    }

    @Test
    void anExistingPartNumberIsRefusedWhateverItsCase() {
        authenticateWith(Permissions.PARTS_EDIT);
        when(partService.fuzzyByPartNumber("AP2112K-3.3TRG1"))
                .thenReturn(List.of(part(5L, "ap2112k-3.3trg1")));

        ObjectNode raw = registry.call("add_part", validPart());

        assertThat(raw.get("isError").asBoolean()).isTrue();
        assertThat(payload(raw).get("error").asText()).contains("id 5");
        verify(partService, never()).create(any());
    }

    @Test
    void suggestCategoryRanksWhereTheMostSimilarPartsWereFiled() {
        PartDTO first = part(1L, "LDO-A");
        first.setCategoryId(271L);
        PartDTO second = part(2L, "BUCK-B");
        second.setCategoryId(272L);
        PartDTO third = part(3L, "LDO-C");
        third.setCategoryId(271L);
        when(partService.mostSimilar(anyString(), anyInt())).thenReturn(List.of(first, second, third));

        JsonNode result = call("suggest_category", args("description", "LDO 3.3V"));

        JsonNode suggestions = result.get("suggestions");
        assertThat(suggestions.get(0).get("id").asLong()).isEqualTo(271L);
        assertThat(suggestions.get(0).get("leaf").asBoolean()).isTrue();
        assertThat(suggestions.get(1).get("id").asLong()).isEqualTo(272L);
    }

    @Test
    void addOrderItemPutsThePartOnTheListWithoutAProject() {
        authenticateWith(Permissions.PARTS_EDIT);
        when(partService.findById(16L)).thenReturn(part(16L, "AP2112K-3.3TRG1"));
        when(orderItemService.create(any())).thenReturn(OrderItemDTO.builder().id(1L).quantity(10).build());

        JsonNode result = call("add_order_item", args("partId", 16, "quantity", 10));

        ArgumentCaptor<OrderItemRequest> request = ArgumentCaptor.forClass(OrderItemRequest.class);
        verify(orderItemService).create(request.capture());
        assertThat(request.getValue().getPartId()).isEqualTo(16L);
        assertThat(request.getValue().getQuantity()).isEqualTo(10);
        assertThat(request.getValue().getProjectId()).isNull();
        assertThat(result.get("orderItemId").asLong()).isEqualTo(1L);
    }

    @Test
    void addOrderItemRefusesAZeroQuantity() {
        authenticateWith(Permissions.PARTS_EDIT);
        when(partService.findById(16L)).thenReturn(part(16L, "AP2112K-3.3TRG1"));

        ObjectNode raw = registry.call("add_order_item", args("partId", 16, "quantity", 0));

        assertThat(raw.get("isError").asBoolean()).isTrue();
        verify(orderItemService, never()).create(any());
    }

    // ------------------------------------------------------------------ helpers

    private ObjectNode validPart() {
        return args("mpn", "AP2112K-3.3TRG1", "manufacturer", "Diodes Inc",
                "description", "LDO regulator 3.3V", "categoryId", 271, "footprint", "SOT-753");
    }

    private void authenticateWith(String... authorities) {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("owner@example.com", null,
                        List.of(authorities).stream().map(SimpleGrantedAuthority::new).toList()));
    }

    private JsonNode call(String tool, ObjectNode arguments) {
        ObjectNode raw = registry.call(tool, arguments);
        assertThat(raw.get("isError").asBoolean())
                .withFailMessage("tool failed: %s", raw.get("content").get(0).get("text").asText())
                .isFalse();
        return payload(raw);
    }

    private JsonNode payload(ObjectNode raw) {
        try {
            return objectMapper.readTree(raw.get("content").get(0).get("text").asText());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private ObjectNode args(Object... keysAndValues) {
        ObjectNode node = objectMapper.createObjectNode();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            Object value = keysAndValues[i + 1];
            if (value instanceof Integer number) {
                node.put((String) keysAndValues[i], number);
            } else {
                node.put((String) keysAndValues[i], (String) value);
            }
        }
        return node;
    }

    private CategoryDTO category(Long id, String name, Long parentId) {
        String breadcrumb = parentId == null ? name : "Power > " + name;
        return CategoryDTO.builder().id(id).name(name).parentId(parentId).breadcrumb(breadcrumb).build();
    }

    private PartDTO part(Long id, String partNumber) {
        PartDTO part = new PartDTO();
        part.setId(id);
        part.setPartNumber(partNumber);
        return part;
    }
}
