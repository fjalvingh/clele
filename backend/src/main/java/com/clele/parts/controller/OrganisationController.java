package com.clele.parts.controller;

import com.clele.parts.dto.OrganisationDTO;
import com.clele.parts.dto.OrganisationRequest;
import com.clele.parts.model.Permissions;
import com.clele.parts.service.CurrentOrganisationService;
import com.clele.parts.service.OrganisationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/organisations")
@RequiredArgsConstructor
public class OrganisationController {

    private final OrganisationService organisationService;
    private final CurrentOrganisationService currentOrganisationService;

    /**
     * The organisations the caller may switch into — their memberships, or everything (including
     * the template) for a Global Administrator. Authenticated-only: this drives the switcher that
     * every user needs.
     */
    @GetMapping("/selectable")
    public List<OrganisationDTO> selectable() {
        return currentOrganisationService.selectable().stream()
                .map(organisationService::toDTO)
                .toList();
    }

    @GetMapping
    @PreAuthorize("hasAuthority('" + Permissions.GLOBAL_ADMIN + "')")
    public List<OrganisationDTO> findAll() {
        return organisationService.findAll();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.GLOBAL_ADMIN + "')")
    public OrganisationDTO findById(@PathVariable Long id) {
        return organisationService.findById(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('" + Permissions.GLOBAL_ADMIN + "')")
    public OrganisationDTO create(@Valid @RequestBody OrganisationRequest request) {
        return organisationService.create(request);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.GLOBAL_ADMIN + "')")
    public OrganisationDTO update(@PathVariable Long id,
                                  @Valid @RequestBody OrganisationRequest request) {
        return organisationService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('" + Permissions.GLOBAL_ADMIN + "')")
    public void delete(@PathVariable Long id) {
        organisationService.delete(id);
    }
}
