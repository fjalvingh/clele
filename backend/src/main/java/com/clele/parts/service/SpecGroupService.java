package com.clele.parts.service;

import com.clele.parts.dto.SpecGroupDTO;
import com.clele.parts.dto.SpecGroupRequest;
import com.clele.parts.model.SpecGroup;
import com.clele.parts.repository.SpecDefinitionRepository;
import com.clele.parts.repository.SpecGroupRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Spec groups — the sections a part's specifications are displayed under, and the folders the
 * Spec Fields screen is organised by. Every spec definition belongs to exactly one.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SpecGroupService {

    /** The group new and rescanned specs land in when the caller names none. */
    static final String DEFAULT_GROUP_NAME = "Technical";

    private final SpecGroupRepository groupRepo;
    private final SpecDefinitionRepository specRepo;
    private final CurrentOrganisationService currentOrganisationService;

    public List<SpecGroupDTO> findAll() {
        return groupRepo.findByOrganisationIdOrderByDisplayOrderAscNameAsc(
                        currentOrganisationService.currentId()).stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    public SpecGroupDTO findById(Long id) {
        return toDTO(requireGroup(id));
    }

    @Transactional
    public SpecGroupDTO create(SpecGroupRequest request) {
        Long orgId = currentOrganisationService.currentId();
        String name = request.getName().trim();
        if (groupRepo.existsByOrganisationIdAndNameIgnoreCase(orgId, name)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A spec group named '" + name + "' already exists");
        }
        SpecGroup group = SpecGroup.builder()
                .organisation(currentOrganisationService.current())
                .name(name)
                .description(request.getDescription())
                .displayOrder(request.getDisplayOrder())
                .build();
        return toDTO(groupRepo.save(group));
    }

    @Transactional
    public SpecGroupDTO update(Long id, SpecGroupRequest request) {
        SpecGroup group = requireGroup(id);
        String name = request.getName().trim();
        if (groupRepo.existsByOrganisationIdAndNameIgnoreCaseAndIdNot(
                currentOrganisationService.currentId(), name, id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A spec group named '" + name + "' already exists");
        }
        group.setName(name);
        group.setDescription(request.getDescription());
        group.setDisplayOrder(request.getDisplayOrder());
        return toDTO(groupRepo.save(group));
    }

    /** Refuses a group that still holds spec fields — move them out first, so none is orphaned. */
    @Transactional
    public void delete(Long id) {
        SpecGroup group = requireGroup(id);
        long specs = specRepo.countByGroupId(id);
        if (specs > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Group still holds " + specs + " spec field(s). Move them to another group first.");
        }
        groupRepo.delete(group);
    }

    /** Groups outside the current organisation are reported as not found. */
    SpecGroup requireGroup(Long id) {
        return groupRepo.findByIdAndOrganisationId(id, currentOrganisationService.currentId())
                .orElseThrow(() -> new EntityNotFoundException("SpecGroup not found: " + id));
    }

    /**
     * The group a spec lands in when none is given: "Technical" if present, otherwise the first
     * group by display order, otherwise a freshly created "Technical" (a brand-new organisation
     * that somehow has none must still be able to take a spec).
     */
    @Transactional
    public SpecGroup defaultGroup() {
        Long orgId = currentOrganisationService.currentId();
        return groupRepo.findByOrganisationIdAndNameIgnoreCase(orgId, DEFAULT_GROUP_NAME)
                .or(() -> groupRepo.findByOrganisationIdOrderByDisplayOrderAscNameAsc(orgId)
                        .stream().findFirst())
                .orElseGet(() -> groupRepo.save(SpecGroup.builder()
                        .organisation(currentOrganisationService.current())
                        .name(DEFAULT_GROUP_NAME)
                        .displayOrder(0)
                        .build()));
    }

    private SpecGroupDTO toDTO(SpecGroup group) {
        return SpecGroupDTO.builder()
                .id(group.getId())
                .name(group.getName())
                .description(group.getDescription())
                .displayOrder(group.getDisplayOrder())
                .specCount(specRepo.countByGroupId(group.getId()))
                .build();
    }
}
