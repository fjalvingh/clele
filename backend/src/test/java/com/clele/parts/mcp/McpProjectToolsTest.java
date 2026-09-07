package com.clele.parts.mcp;

import com.clele.parts.dto.PartDTO;
import com.clele.parts.dto.ProjectDTO;
import com.clele.parts.dto.ProjectPartDTO;
import com.clele.parts.dto.ProjectPartRequest;
import com.clele.parts.model.Permissions;
import com.clele.parts.model.ProjectStatus;
import com.clele.parts.repository.SpecDefinitionRepository;
import com.clele.parts.service.CategoryService;
import com.clele.parts.service.CurrentOrganisationService;
import com.clele.parts.service.LocationService;
import com.clele.parts.service.PartService;
import com.clele.parts.service.ProjectService;
import com.clele.parts.service.SpecDefinitionService;
import com.clele.parts.service.StockEntryService;
import com.clele.parts.service.StockThresholdService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The project tools of the MCP endpoint.
 *
 * <p>Two things are worth pinning here. The first is the <b>write gate</b>: these are the only
 * tools that change anything, and they move stock while doing it, so a credential without
 * PARTS_EDIT must be turned away <i>before</i> the service is reached — a check that lives in the
 * registry precisely because these calls never pass {@code ProjectController}'s
 * {@code @PreAuthorize}. The second is <b>resolution by name</b>: a model has a project name and a
 * part number in hand far more often than ids, and a wrong resolution here silently moves the
 * wrong parts.
 */
class McpProjectToolsTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PartService partService = mock(PartService.class);
    private final ProjectService projectService = mock(ProjectService.class);

    private final McpToolRegistry registry = new McpToolRegistry(
            objectMapper,
            partService,
            mock(StockEntryService.class),
            mock(StockThresholdService.class),
            mock(SpecDefinitionService.class),
            mock(SpecDefinitionRepository.class),
            mock(CategoryService.class),
            mock(LocationService.class),
            projectService,
            mock(CurrentOrganisationService.class));

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    // ------------------------------------------------------------------ reading

    @Test
    void searchProjectsFiltersOnNameAndStatus() {
        when(projectService.findAll()).thenReturn(List.of(
                project(1L, "Synth voice card", ProjectStatus.ACTIVE),
                project(2L, "Synth power supply", ProjectStatus.CANCELLED),
                project(3L, "Bench meter", ProjectStatus.ACTIVE)));

        JsonNode result = call("search_projects", args("query", "synth", "status", "ACTIVE"));

        assertThat(result.get("total").asInt()).isEqualTo(1);
        assertThat(result.get("projects").get(0).get("name").asText()).isEqualTo("Synth voice card");
    }

    @Test
    void searchProjectsRejectsAnUnknownStatusRatherThanIgnoringIt() {
        when(projectService.findAll()).thenReturn(List.of());

        ObjectNode raw = registry.call("search_projects", args("status", "FINISHED"));

        assertThat(raw.get("isError").asBoolean()).isTrue();
        assertThat(payload(raw).get("error").asText())
                .contains("not a project status")
                .contains("ACTIVE");
    }

    @Test
    void getProjectResolvesTheNameAndReturnsTheWholePartsList() {
        ProjectDTO detail = project(7L, "Synth voice card", ProjectStatus.ACTIVE);
        detail.setParts(List.of(line(11L, 100L, "1N4148", 4, 8, 5)));
        when(projectService.findAll()).thenReturn(List.of(project(7L, "Synth voice card", ProjectStatus.ACTIVE)));
        when(projectService.findById(7L)).thenReturn(detail);

        JsonNode result = call("get_project", args("project", "synth VOICE card"));

        assertThat(result.get("id").asLong()).isEqualTo(7L);
        JsonNode part = result.get("parts").get(0);
        assertThat(part.get("partNumber").asText()).isEqualTo("1N4148");
        assertThat(part.get("totalNeeded").asInt()).isEqualTo(8);
        assertThat(part.get("shortfall").asInt()).isEqualTo(5);
    }

    @Test
    void anUnknownProjectNameSuggestsTheOnesItLooksLike() {
        when(projectService.findAll()).thenReturn(List.of(
                project(1L, "Synth voice card", ProjectStatus.ACTIVE)));

        ObjectNode raw = registry.call("get_project", args("project", "synth"));

        assertThat(raw.get("isError").asBoolean()).isTrue();
        assertThat(payload(raw).get("error").asText()).contains("Synth voice card");
    }

    // ------------------------------------------------------------------ the write gate

    @Test
    void addingAPartWithoutPartsEditIsRefusedBeforeTheServiceIsReached() {
        authenticateWith();  // a credential that may read and nothing else

        ObjectNode raw = registry.call("add_project_part", args("projectId", 7, "partId", 100));

        assertThat(raw.get("isError").asBoolean()).isTrue();
        assertThat(payload(raw).get("error").asText()).contains(Permissions.PARTS_EDIT);
        verify(projectService, never()).addPart(any(), any());
        verify(projectService, never()).findById(any());
    }

    @Test
    void removingAPartWithoutPartsEditIsRefusedBeforeTheServiceIsReached() {
        authenticateWith();

        ObjectNode raw = registry.call("remove_project_part", args("projectId", 7, "partId", 100));

        assertThat(raw.get("isError").asBoolean()).isTrue();
        verify(projectService, never()).removePart(any(), any());
    }

    // ------------------------------------------------------------------ writing

    @Test
    void addingAPartResolvesThePartNumberAndReportsWhatStockCouldSupply() {
        authenticateWith(Permissions.PARTS_EDIT);
        when(projectService.findById(7L)).thenReturn(project(7L, "Synth voice card", ProjectStatus.ACTIVE));
        when(partService.fuzzyByPartNumber("1n4148")).thenReturn(List.of(part(100L, "1N4148")));
        when(projectService.addPart(eq(7L), any())).thenReturn(line(11L, 100L, "1N4148", 4, 8, 5));

        JsonNode result = call("add_project_part",
                args("projectId", 7, "partNumber", "1n4148", "qtyPerInstance", 4));

        ArgumentCaptor<ProjectPartRequest> request = ArgumentCaptor.forClass(ProjectPartRequest.class);
        verify(projectService).addPart(eq(7L), request.capture());
        assertThat(request.getValue().getPartId()).isEqualTo(100L);
        assertThat(request.getValue().getQtyPerInstance()).isEqualTo(4);

        assertThat(result.get("added").get("partNumber").asText()).isEqualTo("1N4148");
        assertThat(result.get("takenFromStock").asInt()).isEqualTo(3);
        assertThat(result.get("note").asText()).contains("short");
    }

    @Test
    void removingAPartFindsItsLineAndReportsWhatWentBackToStock() {
        authenticateWith(Permissions.PARTS_EDIT);
        ProjectDTO detail = project(7L, "Synth voice card", ProjectStatus.ACTIVE);
        detail.setParts(List.of(line(11L, 100L, "1N4148", 4, 8, 0)));
        when(projectService.findById(7L)).thenReturn(detail);
        when(partService.findById(100L)).thenReturn(part(100L, "1N4148"));

        JsonNode result = call("remove_project_part", args("projectId", 7, "partId", 100));

        verify(projectService).removePart(7L, 11L);
        assertThat(result.get("returnedToStock").asInt()).isEqualTo(8);
    }

    @Test
    void removingAPartTheProjectDoesNotListSaysWhatItDoesList() {
        authenticateWith(Permissions.PARTS_EDIT);
        ProjectDTO detail = project(7L, "Synth voice card", ProjectStatus.ACTIVE);
        detail.setParts(List.of(line(11L, 100L, "1N4148", 4, 8, 0)));
        when(projectService.findById(7L)).thenReturn(detail);
        when(partService.findById(200L)).thenReturn(part(200L, "BC547"));

        ObjectNode raw = registry.call("remove_project_part", args("projectId", 7, "partId", 200));

        assertThat(raw.get("isError").asBoolean()).isTrue();
        assertThat(payload(raw).get("error").asText())
                .contains("BC547")
                .contains("1N4148");
        verify(projectService, never()).removePart(any(), any());
    }

    // ------------------------------------------------------------------ helpers

    private void authenticateWith(String... authorities) {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("owner@example.com", null,
                        List.of(authorities).stream().map(SimpleGrantedAuthority::new).toList()));
    }

    /** The payload of a call that must have succeeded. */
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

    private ProjectDTO project(Long id, String name, ProjectStatus status) {
        return ProjectDTO.builder()
                .id(id).name(name).status(status).instanceCount(2).partCount(1)
                .build();
    }

    private ProjectPartDTO line(Long id, Long partId, String partNumber,
                                int qtyPerInstance, int totalNeeded, int shortfall) {
        return ProjectPartDTO.builder()
                .id(id).partId(partId).partNumber(partNumber).partName("Small signal diode")
                .qtyPerInstance(qtyPerInstance).totalNeeded(totalNeeded)
                .qtyAllocated(totalNeeded - shortfall).shortfall(shortfall)
                .build();
    }

    private PartDTO part(Long id, String partNumber) {
        PartDTO part = new PartDTO();
        part.setId(id);
        part.setPartNumber(partNumber);
        return part;
    }
}
