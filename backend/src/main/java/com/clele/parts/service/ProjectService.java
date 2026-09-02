package com.clele.parts.service;

import com.clele.parts.dto.*;
import com.clele.parts.model.*;
import com.clele.parts.repository.*;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

/**
 * A project and its <b>part list</b>.
 *
 * <p>Two phases, and the difference between them is where the parts physically are. While a project
 * is {@link ProjectStatus#ACTIVE} every line of its part list is held by the project, taken out of
 * stock the moment it was added; cancelling gives all of it back and keeps the needed quantities,
 * and reactivating takes it out again. Nothing can be entered while cancelled.
 *
 * <p>The part list is <b>not</b> the BOM. The BOM is the file uploaded into
 * {@link com.clele.parts.service.bom.ProjectBomService}, whose "apply" step feeds this list.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final ProjectPartRepository projectPartRepository;
    private final ProjectStockRepository projectStockRepository;
    private final ProjectBomRepository projectBomRepository;
    private final PartRepository partRepository;
    private final ProjectAllocationService allocationService;
    private final CurrentUserService currentUserService;
    private final CurrentOrganisationService currentOrganisationService;

    public List<ProjectDTO> findAll() {
        AppUser me = currentUserService.current();
        return projectRepository.findByOrganisationIdAndOwnerIdAndDeletedFalseOrderByUpdatedAtDesc(
                        currentOrganisationService.currentId(), me.getId()).stream()
                .map(this::toSummaryDTO)
                .collect(Collectors.toList());
    }

    public ProjectDTO findById(Long id) {
        Project project = requireOwnProject(id);
        return toDetailDTO(project, projectPartRepository.findByProjectIdWithPart(id));
    }

    @Transactional
    public ProjectDTO create(ProjectRequest request) {
        AppUser me = currentUserService.current();
        Project project = Project.builder()
                .name(request.getName())
                .description(request.getDescription())
                .instanceCount(request.getInstanceCount())
                .status(ProjectStatus.ACTIVE)
                .owner(me)
                .organisation(currentOrganisationService.current())
                .build();
        return toSummaryDTO(projectRepository.save(project));
    }

    /**
     * Renames a project or changes what it builds. Raising the instance count raises every line's
     * need, so the allocation is topped up in the same step — the alternative is a project that
     * silently claims to be short of parts that are sitting on the shelf.
     */
    @Transactional
    public ProjectDTO update(Long id, ProjectRequest request) {
        Project project = requireActiveProject(id);
        project.setName(request.getName());
        project.setDescription(request.getDescription());
        boolean instancesChanged = project.getInstanceCount() != request.getInstanceCount();
        project.setInstanceCount(request.getInstanceCount());
        projectRepository.save(project);

        if (instancesChanged) {
            List<ProjectPart> parts = projectPartRepository.findByProjectIdWithPart(id);
            parts.forEach(pp -> syncAllocation(project, pp));
            projectPartRepository.saveAll(parts);
        }
        return toSummaryDTO(project);
    }

    /**
     * Deletes a cancelled project and everything it owns.
     *
     * <p>The project row itself is only <b>logically</b> deleted: {@code stock_movement.project_id}
     * still points at it, so the ledger can go on saying which project a PROJECT_OUT or
     * PROJECT_RETURN belonged to. The part list, the allocation batches and the imported BOM are
     * deleted for real — a cancelled project holds no stock, so nothing is lost by that.
     */
    @Transactional
    public void delete(Long id) {
        Project project = requireOwnProject(id);
        if (project.getStatus() != ProjectStatus.CANCELLED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Only a cancelled project can be deleted");
        }
        projectBomRepository.findByProjectId(id).ifPresent(projectBomRepository::delete);
        projectStockRepository.deleteByProjectId(id);
        projectPartRepository.deleteByProjectId(id);
        project.setDeleted(true);
        projectRepository.save(project);
    }

    // ------------------------------------------------------------------
    // Part list
    // ------------------------------------------------------------------

    /** Adds a part to the list and immediately takes what the build needs out of stock. */
    @Transactional
    public ProjectPartDTO addPart(Long projectId, ProjectPartRequest request) {
        Project project = requireActiveProject(projectId);
        if (projectPartRepository.existsByProjectIdAndPartId(projectId, request.getPartId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "That part is already on the project's part list");
        }
        Part part = partRepository.findByIdAndOrganisationId(
                        request.getPartId(), currentOrganisationService.currentId())
                .orElseThrow(() -> new EntityNotFoundException("Part not found: " + request.getPartId()));

        ProjectPart pp = ProjectPart.builder()
                .project(project)
                .part(part)
                .qtyPerInstance(request.getQtyPerInstance())
                .qtyAllocated(0)
                .notes(request.getNotes())
                .build();
        syncAllocation(project, pp);
        return toPartDTO(projectPartRepository.save(pp), project.getInstanceCount());
    }

    /** Changes how many the build needs, allocating the difference or giving the excess back. */
    @Transactional
    public ProjectPartDTO updatePart(Long projectId, Long projectPartId, ProjectPartRequest request) {
        Project project = requireActiveProject(projectId);
        ProjectPart pp = requireOwnPart(projectId, projectPartId);
        pp.setQtyPerInstance(request.getQtyPerInstance());
        pp.setNotes(request.getNotes());
        syncAllocation(project, pp);
        return toPartDTO(projectPartRepository.save(pp), project.getInstanceCount());
    }

    /** Takes a part off the list, returning everything the project held of it. */
    @Transactional
    public void removePart(Long projectId, Long projectPartId) {
        Project project = requireActiveProject(projectId);
        ProjectPart pp = requireOwnPart(projectId, projectPartId);
        allocationService.release(project, pp.getPart(), pp.getQtyAllocated());
        projectPartRepository.delete(pp);
    }

    /**
     * Gives some of one line's allocation back to the locations it came from. The line stays on the
     * list with its need intact, so the shortfall is visible until the parts are fetched again.
     */
    @Transactional
    public ProjectPartDTO returnPart(Long projectId, Long projectPartId, ReturnPartRequest request) {
        Project project = requireActiveProject(projectId);
        ProjectPart pp = requireOwnPart(projectId, projectPartId);
        if (request.getQuantity() > pp.getQtyAllocated()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "The project only holds " + pp.getQtyAllocated() + " of that part");
        }
        int returned = allocationService.release(project, pp.getPart(), request.getQuantity());
        pp.setQtyAllocated(pp.getQtyAllocated() - returned);
        return toPartDTO(projectPartRepository.save(pp), project.getInstanceCount());
    }

    // ------------------------------------------------------------------
    // Phase transitions
    // ------------------------------------------------------------------

    /** Cancels the project: every allocation goes back to stock, every needed quantity stays. */
    @Transactional
    public ProjectDTO cancel(Long id) {
        Project project = requireOwnProject(id);
        if (project.getStatus() == ProjectStatus.CANCELLED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "The project is already cancelled");
        }
        List<ProjectPart> parts = projectPartRepository.findByProjectIdWithPart(id);
        for (ProjectPart pp : parts) {
            allocationService.release(project, pp.getPart(), pp.getQtyAllocated());
            pp.setQtyAllocated(0);
        }
        projectPartRepository.saveAll(parts);
        project.setStatus(ProjectStatus.CANCELLED);
        projectRepository.save(project);
        return toDetailDTO(project, parts);
    }

    /**
     * Reactivates a cancelled project, fetching every part list line out of stock again.
     *
     * <p>The parts are taken from wherever they are now, not from the location they were returned
     * to — stock moves while a project sits cancelled. Lines the shelf can no longer cover come back
     * short, which is reported rather than refused.
     */
    @Transactional
    public ProjectDTO activate(Long id) {
        Project project = requireOwnProject(id);
        if (project.getStatus() == ProjectStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "The project is already active");
        }
        project.setStatus(ProjectStatus.ACTIVE);
        projectRepository.save(project);

        List<ProjectPart> parts = projectPartRepository.findByProjectIdWithPart(id);
        parts.forEach(pp -> syncAllocation(project, pp));
        projectPartRepository.saveAll(parts);
        return toDetailDTO(project, parts);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Brings one line's allocation in line with what the build needs: fetch the difference, or give
     * the excess back. Public because {@code ProjectBomService.apply} pushes quantities straight
     * into {@code project_part} and must settle the stock the same way.
     */
    public void syncAllocation(Project project, ProjectPart pp) {
        int needed = pp.totalNeeded(project.getInstanceCount());
        int held = pp.getQtyAllocated();
        if (held < needed) {
            pp.setQtyAllocated(held + allocationService.allocate(project, pp.getPart(), needed - held));
        } else if (held > needed) {
            pp.setQtyAllocated(held - allocationService.release(project, pp.getPart(), held - needed));
        }
    }

    /**
     * Resolves a project the caller may act on — scoped to the organisation in force <em>and</em>
     * to the caller, since projects are private to their owner. A project belonging to someone else,
     * to another organisation, or logically deleted is reported as 404, not 403: as far as this
     * caller is concerned it does not exist.
     *
     * <p>Public because the BOM-import services enforce the same rule; one definition of it is the
     * point.
     */
    public Project requireOwnProject(Long id) {
        AppUser me = currentUserService.current();
        return projectRepository.findByIdAndOrganisationIdAndOwnerIdAndDeletedFalse(
                        id, currentOrganisationService.currentId(), me.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Project not found: " + id));
    }

    /** The same, refusing anything that would write to a cancelled project. */
    public Project requireActiveProject(Long id) {
        Project project = requireOwnProject(id);
        if (project.getStatus() != ProjectStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A cancelled project cannot be changed — reactivate it first");
        }
        return project;
    }

    private ProjectPart requireOwnPart(Long projectId, Long projectPartId) {
        ProjectPart pp = projectPartRepository.findById(projectPartId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Part list entry not found: " + projectPartId));
        if (!pp.getProject().getId().equals(projectId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Part list entry not found: " + projectPartId);
        }
        return pp;
    }

    private ProjectDTO toSummaryDTO(Project p) {
        return baseDTO(p)
                .partCount(projectPartRepository.countByProjectId(p.getId()))
                .anyShortfall(projectPartRepository.existsShortfall(p.getId(), p.getInstanceCount()))
                .build();
    }

    private ProjectDTO toDetailDTO(Project p, List<ProjectPart> parts) {
        List<ProjectPartDTO> partDTOs = parts.stream()
                .map(pp -> toPartDTO(pp, p.getInstanceCount()))
                .collect(Collectors.toList());

        BigDecimal totalValue = projectStockRepository.findByProjectIdWithDetails(p.getId()).stream()
                .filter(ps -> ps.getUnitPrice() != null)
                .map(ps -> ps.getUnitPrice().multiply(BigDecimal.valueOf(ps.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return baseDTO(p)
                .partCount(parts.size())
                .anyShortfall(partDTOs.stream().anyMatch(dto -> dto.getShortfall() > 0))
                .totalStockValue(totalValue)
                .parts(partDTOs)
                .build();
    }

    private ProjectDTO.ProjectDTOBuilder baseDTO(Project p) {
        return ProjectDTO.builder()
                .id(p.getId())
                .name(p.getName())
                .description(p.getDescription())
                .status(p.getStatus())
                .instanceCount(p.getInstanceCount())
                .ownerId(p.getOwner().getId())
                .ownerName(displayName(p.getOwner()))
                .createdAt(p.getCreatedAt())
                .updatedAt(p.getUpdatedAt());
    }

    private ProjectPartDTO toPartDTO(ProjectPart pp, int instanceCount) {
        int needed = pp.totalNeeded(instanceCount);
        return ProjectPartDTO.builder()
                .id(pp.getId())
                .partId(pp.getPart().getId())
                .partName(pp.getPart().getDescription())
                .partNumber(pp.getPart().getPartNumber())
                .qtyPerInstance(pp.getQtyPerInstance())
                .totalNeeded(needed)
                .qtyAllocated(pp.getQtyAllocated())
                .shortfall(Math.max(0, needed - pp.getQtyAllocated()))
                .notes(pp.getNotes())
                .build();
    }

    private String displayName(AppUser user) {
        return user.getFullName() != null ? user.getFullName() : user.getEmail();
    }
}
