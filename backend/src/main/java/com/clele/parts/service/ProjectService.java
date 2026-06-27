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
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final ProjectPartRepository projectPartRepository;
    private final ProjectStockRepository projectStockRepository;
    private final PartRepository partRepository;
    private final LocationRepository locationRepository;
    private final StockEntryRepository stockEntryRepository;
    private final StockMovementService stockMovementService;
    private final CurrentUserService currentUserService;

    public List<ProjectDTO> findAll() {
        AppUser me = currentUserService.current();
        return projectRepository.findByOwnerIdOrderByUpdatedAtDesc(me.getId()).stream()
                .map(this::toSummaryDTO)
                .collect(Collectors.toList());
    }

    public ProjectDTO findById(Long id) {
        Project project = requireOwnProject(id);
        List<ProjectPart> bom = projectPartRepository.findByProjectIdWithPart(id);
        List<ProjectStock> stock = projectStockRepository.findByProjectIdWithDetails(id);
        return toDetailDTO(project, bom, stock);
    }

    @Transactional
    public ProjectDTO create(ProjectRequest request) {
        AppUser me = currentUserService.current();
        Project project = Project.builder()
                .name(request.getName())
                .description(request.getDescription())
                .instanceCount(request.getInstanceCount())
                .status(ProjectStatus.PLANNING)
                .owner(me)
                .build();
        return toSummaryDTO(projectRepository.save(project));
    }

    @Transactional
    public ProjectDTO update(Long id, ProjectRequest request) {
        Project project = requireOwnProject(id);
        if (project.getStatus() != ProjectStatus.PLANNING) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Can only edit projects in PLANNING status");
        }
        project.setName(request.getName());
        project.setDescription(request.getDescription());
        project.setInstanceCount(request.getInstanceCount());
        return toSummaryDTO(projectRepository.save(project));
    }

    @Transactional
    public void delete(Long id) {
        Project project = requireOwnProject(id);
        if (project.getStatus() != ProjectStatus.PLANNING) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Can only delete projects in PLANNING status");
        }
        projectRepository.delete(project);
    }

    // ------------------------------------------------------------------
    // BOM management
    // ------------------------------------------------------------------

    @Transactional
    public ProjectBomEntryDTO addBomEntry(Long projectId, ProjectBomRequest request) {
        Project project = requireOwnProject(projectId);
        if (project.getStatus() != ProjectStatus.PLANNING) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "BOM can only be modified in PLANNING status");
        }
        if (projectPartRepository.existsByProjectIdAndPartId(projectId, request.getPartId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Part is already in the BOM");
        }
        Part part = partRepository.findById(request.getPartId())
                .orElseThrow(() -> new EntityNotFoundException("Part not found: " + request.getPartId()));
        ProjectPart pp = ProjectPart.builder()
                .project(project)
                .part(part)
                .qtyPerInstance(request.getQtyPerInstance())
                .notes(request.getNotes())
                .build();
        return toBomDTO(projectPartRepository.save(pp), project);
    }

    @Transactional
    public ProjectBomEntryDTO updateBomEntry(Long projectId, Long bomId, ProjectBomRequest request) {
        Project project = requireOwnProject(projectId);
        ProjectPart pp = projectPartRepository.findById(bomId)
                .orElseThrow(() -> new EntityNotFoundException("BOM entry not found: " + bomId));
        if (!pp.getProject().getId().equals(projectId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "BOM entry does not belong to this project");
        }
        pp.setQtyPerInstance(request.getQtyPerInstance());
        pp.setNotes(request.getNotes());
        return toBomDTO(projectPartRepository.save(pp), project);
    }

    @Transactional
    public void removeBomEntry(Long projectId, Long bomId) {
        Project project = requireOwnProject(projectId);
        if (project.getStatus() != ProjectStatus.PLANNING) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "BOM can only be modified in PLANNING status");
        }
        ProjectPart pp = projectPartRepository.findById(bomId)
                .orElseThrow(() -> new EntityNotFoundException("BOM entry not found: " + bomId));
        if (!pp.getProject().getId().equals(projectId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "BOM entry does not belong to this project");
        }
        projectPartRepository.delete(pp);
    }

    // ------------------------------------------------------------------
    // State transitions
    // ------------------------------------------------------------------

    @Transactional
    public ProjectDTO startBuild(Long id) {
        Project project = requireOwnProject(id);
        if (project.getStatus() != ProjectStatus.PLANNING) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Project must be in PLANNING status to start build");
        }
        project.setStatus(ProjectStatus.BUILDING);
        return toSummaryDTO(projectRepository.save(project));
    }

    @Transactional
    public ProjectStockEntryDTO pullStock(Long projectId, PullStockRequest request) {
        Project project = requireOwnProject(projectId);
        if (project.getStatus() != ProjectStatus.BUILDING) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Project must be in BUILDING status to pull stock");
        }
        Part part = partRepository.findById(request.getPartId())
                .orElseThrow(() -> new EntityNotFoundException("Part not found: " + request.getPartId()));
        Location location = locationRepository.findById(request.getLocationId())
                .orElseThrow(() -> new EntityNotFoundException("Location not found: " + request.getLocationId()));

        BigDecimal price = request.getUnitPrice();
        if (price == null) {
            price = stockEntryRepository.findByPartIdAndLocationId(part.getId(), location.getId())
                    .map(StockEntry::getUnitPrice).orElse(null);
        }

        StockMovement movement = stockMovementService.applyForProject(
                part, location, -request.getQuantity(), price,
                "Pulled for project: " + project.getName(),
                MovementType.PROJECT_OUT, project);

        AppUser me = currentUserService.current();
        ProjectStock ps = ProjectStock.builder()
                .project(project)
                .part(part)
                .location(location)
                .quantity(request.getQuantity())
                .unitPrice(price)
                .movement(movement)
                .addedAt(LocalDateTime.now())
                .addedByUser(me)
                .build();
        return toStockDTO(projectStockRepository.save(ps));
    }

    @Transactional
    public ProjectDTO complete(Long id) {
        Project project = requireOwnProject(id);
        if (project.getStatus() != ProjectStatus.BUILDING) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Project must be in BUILDING status to complete");
        }
        project.setStatus(ProjectStatus.COMPLETED);
        return toSummaryDTO(projectRepository.save(project));
    }

    @Transactional
    public ProjectDTO cancel(Long id, CancelRequest request) {
        Project project = requireOwnProject(id);
        if (project.getStatus() == ProjectStatus.COMPLETED || project.getStatus() == ProjectStatus.CANCELLED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Project cannot be cancelled in its current state");
        }

        if (request.getReturnStockIds() != null) {
            for (Long psId : request.getReturnStockIds()) {
                ProjectStock ps = projectStockRepository.findById(psId)
                        .orElseThrow(() -> new EntityNotFoundException("Project stock entry not found: " + psId));
                if (!ps.getProject().getId().equals(id)) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                            "Stock entry does not belong to this project");
                }
                stockMovementService.applyForProject(
                        ps.getPart(), ps.getLocation(), ps.getQuantity(), ps.getUnitPrice(),
                        "Returned from cancelled project: " + project.getName(),
                        MovementType.PROJECT_RETURN, project);
            }
        }

        project.setStatus(ProjectStatus.CANCELLED);
        return toSummaryDTO(projectRepository.save(project));
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private Project requireOwnProject(Long id) {
        AppUser me = currentUserService.current();
        return projectRepository.findByIdAndOwnerId(id, me.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Project not found: " + id));
    }

    private ProjectDTO toSummaryDTO(Project p) {
        int bomCount = projectPartRepository.countByProjectId(p.getId());
        return ProjectDTO.builder()
                .id(p.getId())
                .name(p.getName())
                .description(p.getDescription())
                .status(p.getStatus())
                .instanceCount(p.getInstanceCount())
                .ownerId(p.getOwner().getId())
                .ownerName(displayName(p.getOwner()))
                .bomPartCount(bomCount)
                .createdAt(p.getCreatedAt())
                .updatedAt(p.getUpdatedAt())
                .build();
    }

    private ProjectDTO toDetailDTO(Project p, List<ProjectPart> bom, List<ProjectStock> stock) {
        List<ProjectBomEntryDTO> bomDTOs = bom.stream()
                .map(pp -> {
                    int pulled = projectStockRepository.sumQuantityByProjectIdAndPartId(
                            p.getId(), pp.getPart().getId());
                    return toBomDTOWithPulled(pp, p.getInstanceCount(), pulled);
                })
                .collect(Collectors.toList());

        List<ProjectStockEntryDTO> stockDTOs = stock.stream()
                .map(this::toStockDTO)
                .collect(Collectors.toList());

        BigDecimal totalValue = stock.stream()
                .filter(ps -> ps.getUnitPrice() != null)
                .map(ps -> ps.getUnitPrice().multiply(BigDecimal.valueOf(ps.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return ProjectDTO.builder()
                .id(p.getId())
                .name(p.getName())
                .description(p.getDescription())
                .status(p.getStatus())
                .instanceCount(p.getInstanceCount())
                .ownerId(p.getOwner().getId())
                .ownerName(displayName(p.getOwner()))
                .bomPartCount(bom.size())
                .totalStockValue(totalValue)
                .bom(bomDTOs)
                .stock(stockDTOs)
                .createdAt(p.getCreatedAt())
                .updatedAt(p.getUpdatedAt())
                .build();
    }

    private ProjectBomEntryDTO toBomDTO(ProjectPart pp, Project project) {
        int pulled = projectStockRepository.sumQuantityByProjectIdAndPartId(
                project.getId(), pp.getPart().getId());
        return toBomDTOWithPulled(pp, project.getInstanceCount(), pulled);
    }

    private ProjectBomEntryDTO toBomDTOWithPulled(ProjectPart pp, int instanceCount, int pulledTotal) {
        return ProjectBomEntryDTO.builder()
                .id(pp.getId())
                .partId(pp.getPart().getId())
                .partName(pp.getPart().getName())
                .partNumber(pp.getPart().getPartNumber())
                .qtyPerInstance(pp.getQtyPerInstance())
                .totalNeeded(pp.getQtyPerInstance() * instanceCount)
                .pulledTotal(pulledTotal)
                .notes(pp.getNotes())
                .build();
    }

    private ProjectStockEntryDTO toStockDTO(ProjectStock ps) {
        String addedByName = ps.getAddedByUser() != null ? displayName(ps.getAddedByUser()) : null;
        return ProjectStockEntryDTO.builder()
                .id(ps.getId())
                .partId(ps.getPart().getId())
                .partName(ps.getPart().getName())
                .partNumber(ps.getPart().getPartNumber())
                .locationId(ps.getLocation().getId())
                .locationName(ps.getLocation().getName())
                .locationBreadcrumb(ps.getLocation().breadcrumb())
                .quantity(ps.getQuantity())
                .unitPrice(ps.getUnitPrice())
                .movementId(ps.getMovement() != null ? ps.getMovement().getId() : null)
                .addedAt(ps.getAddedAt())
                .addedByName(addedByName)
                .build();
    }

    private String displayName(AppUser user) {
        return user.getFullName() != null ? user.getFullName() : user.getEmail();
    }
}
