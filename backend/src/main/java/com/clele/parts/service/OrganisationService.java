package com.clele.parts.service;

import com.clele.parts.dto.OrganisationDTO;
import com.clele.parts.dto.OrganisationRequest;
import com.clele.parts.model.Category;
import com.clele.parts.model.Organisation;
import com.clele.parts.model.SpecAlias;
import com.clele.parts.model.SpecDefinition;
import com.clele.parts.model.SpecGroup;
import com.clele.parts.model.Tag;
import com.clele.parts.repository.*;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * CRUD for organisations. Creating one clones the template organisation's categories, spec fields
 * and tags into it, so a new tenant starts with a usable taxonomy rather than an empty screen —
 * the same content the V36 migration seeded the template with. Parts, locations, stock and projects
 * are never cloned; those are the tenant's own data.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrganisationService {

    private final OrganisationRepository organisationRepository;
    private final CategoryRepository categoryRepository;
    private final SpecDefinitionRepository specDefinitionRepository;
    private final SpecGroupRepository specGroupRepository;
    private final SpecAliasRepository specAliasRepository;
    private final TagRepository tagRepository;
    private final PartRepository partRepository;
    private final LocationRepository locationRepository;
    private final ProjectRepository projectRepository;

    public List<OrganisationDTO> findAll() {
        return organisationRepository.findAllByOrderByName().stream().map(this::toDTO).toList();
    }

    public OrganisationDTO findById(Long id) {
        return toDTO(get(id));
    }

    public Organisation get(Long id) {
        return organisationRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Organisation not found: " + id));
    }

    @Transactional
    public OrganisationDTO create(OrganisationRequest request) {
        String name = request.getName().trim();
        if (organisationRepository.existsByNameIgnoreCase(name)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "An organisation named '" + name + "' already exists");
        }
        Organisation organisation = organisationRepository.save(Organisation.builder()
                .name(name)
                .description(request.getDescription())
                .template(false)
                .build());

        organisationRepository.findByTemplateTrue()
                .ifPresent(template -> copyContent(template, organisation));

        return toDTO(organisation);
    }

    @Transactional
    public OrganisationDTO update(Long id, OrganisationRequest request) {
        Organisation organisation = get(id);
        String name = request.getName().trim();
        if (organisationRepository.existsByNameIgnoreCaseAndIdNot(name, id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "An organisation named '" + name + "' already exists");
        }
        organisation.setName(name);
        organisation.setDescription(request.getDescription());
        return toDTO(organisationRepository.save(organisation));
    }

    /**
     * Delete an empty organisation. Refuses the template (new organisations are cloned from it) and
     * refuses one that still holds parts, locations or projects — deleting a live catalogue should
     * be a deliberate, itemised act, not a side effect.
     */
    @Transactional
    public void delete(Long id) {
        Organisation organisation = get(id);
        if (organisation.isTemplate()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "The template organisation cannot be deleted");
        }
        long parts = partRepository.countByOrganisationId(id);
        long locations = locationRepository.countByOrganisationId(id);
        long projects = projectRepository.countByOrganisationIdAndDeletedFalse(id);
        if (parts > 0 || locations > 0 || projects > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Organisation still holds " + parts + " part(s), " + locations
                            + " location(s) and " + projects + " project(s). Remove them first.");
        }
        organisationRepository.delete(organisation);
    }

    /**
     * Copy the taxonomy, spec fields and tags from one organisation into another. Categories are
     * copied parents-first so {@code parent} can be remapped to the copy, and the category↔spec
     * links are rebuilt against the copies.
     */
    private void copyContent(Organisation from, Organisation to) {
        // Groups first — a spec definition cannot exist without one.
        Map<Long, SpecGroup> groupCopies = new HashMap<>();
        for (SpecGroup source :
                specGroupRepository.findByOrganisationIdOrderByDisplayOrderAscNameAsc(from.getId())) {
            SpecGroup copy = SpecGroup.builder()
                    .organisation(to)
                    .name(source.getName())
                    .description(source.getDescription())
                    .displayOrder(source.getDisplayOrder())
                    .build();
            groupCopies.put(source.getId(), specGroupRepository.save(copy));
        }

        for (SpecDefinition source :
                specDefinitionRepository.findByOrganisationIdOrderByDisplayOrderAscNameAsc(from.getId())) {
            SpecDefinition copy = SpecDefinition.builder()
                    .organisation(to)
                    .jsonName(source.getJsonName())
                    .name(source.getName())
                    .dataType(source.getDataType())
                    .unit(source.getUnit())
                    .metricPrefix(source.isMetricPrefix())
                    .options(source.getOptions())
                    .displayOrder(source.getDisplayOrder())
                    .group(groupCopies.get(source.getGroup().getId()))
                    .build();
            SpecDefinition savedSpec = specDefinitionRepository.save(copy);

            // The alternate names the spec is known by travel with it, or the copy would start
            // re-accumulating the duplicates the original had already merged away.
            for (SpecAlias alias :
                    specAliasRepository.findBySpecDefinitionIdOrderByJsonNameAsc(source.getId())) {
                specAliasRepository.save(SpecAlias.builder()
                        .specDefinition(savedSpec)
                        .organisation(to)
                        .jsonName(alias.getJsonName())
                        .build());
            }
        }

        for (Tag source : tagRepository.findByOrganisationId(from.getId())) {
            tagRepository.save(Tag.builder()
                    .organisation(to)
                    .name(source.getName())
                    .build());
        }

        Map<Long, Category> categoryCopies = new HashMap<>();
        for (Category source : orderedParentsFirst(from)) {
            Category copy = Category.builder()
                    .organisation(to)
                    .name(source.getName())
                    .description(source.getDescription())
                    .parent(source.getParent() == null
                            ? null
                            : categoryCopies.get(source.getParent().getId()))
                    .build();
            categoryCopies.put(source.getId(), categoryRepository.save(copy));
        }
    }

    /**
     * The organisation's categories with every parent ahead of its children, so a copy can always
     * resolve its parent's copy. Walks the tree from the roots down rather than sorting by id,
     * which is not guaranteed to be topological.
     */
    private List<Category> orderedParentsFirst(Organisation organisation) {
        Map<Long, List<Category>> byParent = new HashMap<>();
        for (Category category : categoryRepository.findByOrganisationIdOrderByName(organisation.getId())) {
            Long parentId = category.getParent() == null ? null : category.getParent().getId();
            byParent.computeIfAbsent(parentId, key -> new ArrayList<>()).add(category);
        }
        List<Category> ordered = new ArrayList<>();
        collectChildren(byParent, null, ordered);
        return ordered;
    }

    private void collectChildren(Map<Long, List<Category>> byParent, Long parentId,
                                 List<Category> into) {
        for (Category category : byParent.getOrDefault(parentId, List.of())) {
            into.add(category);
            collectChildren(byParent, category.getId(), into);
        }
    }

    public OrganisationDTO toDTO(Organisation organisation) {
        return OrganisationDTO.builder()
                .id(organisation.getId())
                .name(organisation.getName())
                .description(organisation.getDescription())
                .template(organisation.isTemplate())
                .createdAt(organisation.getCreatedAt())
                .updatedAt(organisation.getUpdatedAt())
                .build();
    }
}
