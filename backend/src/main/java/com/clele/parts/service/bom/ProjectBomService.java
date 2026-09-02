package com.clele.parts.service.bom;

import com.clele.parts.dto.*;
import com.clele.parts.model.*;
import com.clele.parts.repository.*;
import com.clele.parts.service.CurrentOrganisationService;
import com.clele.parts.service.PartService;
import com.clele.parts.service.ProjectService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Reads and edits a project's imported BOM: the matching screen's whole backend, plus the step that
 * pushes what has been matched into the project's part list.
 *
 * <p>Matching is deliberately incremental. Every decision is one call and is stored immediately, so
 * the user can close the screen at any point and pick it up days later with nothing lost.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProjectBomService {

    /** How many suggestions to offer per line. Enough to choose from, few enough to read. */
    private static final int CANDIDATE_LIMIT = 10;

    private final ProjectBomRepository bomRepository;
    private final ProjectBomLineRepository lineRepository;
    private final ProjectPartRepository projectPartRepository;
    private final PartRepository partRepository;
    private final StockEntryRepository stockEntryRepository;
    private final PartService partService;
    private final ProjectService projectService;
    private final CurrentOrganisationService currentOrganisationService;

    // ------------------------------------------------------------------
    // Reading
    // ------------------------------------------------------------------

    /** The project's BOM, or null when nothing has been imported yet. */
    public ProjectBomDTO find(Long projectId) {
        Project project = projectService.requireOwnProject(projectId);
        ProjectBom bom = bomRepository.findByProjectIdWithUploader(projectId).orElse(null);
        if (bom == null) {
            return null;
        }
        List<ProjectBomLine> lines = lineRepository.findByBomIdWithPart(bom.getId());
        return toDTO(project, bom, lines);
    }

    /** The uploaded file itself, for the download link. */
    public ProjectBom requireBom(Long projectId) {
        projectService.requireOwnProject(projectId);
        return bomRepository.findByProjectId(projectId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No BOM has been imported for this project"));
    }

    // ------------------------------------------------------------------
    // Matching
    // ------------------------------------------------------------------

    /**
     * Ranked suggestions for one line: parts whose part number or MPN is trigram-similar to the
     * line's MPN, then to its value. Exact hits are marked as such and sorted first.
     *
     * <p>Computed on demand, per line, rather than for the whole BOM on load — a hundred-line BOM
     * would otherwise fire a hundred trigram queries to fill a column the user reads a few rows of.
     */
    public List<BomCandidateDTO> candidates(Long projectId, Long lineId) {
        projectService.requireOwnProject(projectId);
        ProjectBomLine line = requireLine(projectId, lineId);
        Long orgId = currentOrganisationService.currentId();

        // Ordered by id so the "first term wins" preference survives the batched part load.
        Map<Long, Double> scores = new LinkedHashMap<>();
        Map<Long, String> terms = new LinkedHashMap<>();
        for (Map.Entry<String, String> term : searchTerms(line).entrySet()) {
            for (PartRepository.PartMatchView view :
                    partRepository.fuzzyByPartNumberOrMpn(orgId, term.getValue(), CANDIDATE_LIMIT)) {
                scores.merge(view.getId(), view.getScore() == null ? 0.0 : view.getScore(), Math::max);
                terms.putIfAbsent(view.getId(), term.getKey());
            }
        }
        if (scores.isEmpty()) {
            return List.of();
        }

        Set<String> exactTerms = searchTerms(line).values().stream()
                .map(t -> t.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());

        List<Part> parts = partRepository.findAllById(scores.keySet());
        return partService.toDTOsWithStock(parts).stream()
                .map(dto -> {
                    boolean exact = isExact(dto, exactTerms);
                    return BomCandidateDTO.builder()
                            .part(dto)
                            .score(scores.getOrDefault(dto.getId(), 0.0))
                            .exact(exact)
                            .matchedOn(terms.get(dto.getId()))
                            .build();
                })
                .sorted(Comparator.comparing(BomCandidateDTO::isExact).reversed()
                        .thenComparing(Comparator.comparingDouble(BomCandidateDTO::getScore).reversed()))
                .collect(Collectors.toList());
    }

    /**
     * Records a decision about one line. A {@code partId} matches it; a bare status records
     * PROVIDED / EXCLUDED / UNMATCHED.
     *
     * <p>The part is re-resolved against the current organisation rather than trusted from the
     * request, and any {@code changed} flag clears — whatever prompted the review, the user has now
     * looked at the line.
     */
    @Transactional
    public ProjectBomLineDTO setMatch(Long projectId, Long lineId, BomLineMatchRequest request) {
        Project project = projectService.requireOwnProject(projectId);
        ProjectBomLine line = requireLine(projectId, lineId);

        BomLineStatus status = request.getStatus() != null
                ? request.getStatus()
                : (request.getPartId() != null ? BomLineStatus.MATCHED : BomLineStatus.UNMATCHED);

        if (status == BomLineStatus.MATCHED && request.getPartId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A matched line needs a part");
        }

        if (request.getPartId() != null) {
            Part part = partRepository.findByIdAndOrganisationId(
                            request.getPartId(), currentOrganisationService.currentId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                            "Part not found: " + request.getPartId()));
            line.setPart(part);
            line.setMatchSource(BomMatchSource.MANUAL);
        } else {
            // PROVIDED and EXCLUDED are statements about the line, not about a part — clear any
            // match so the two can never disagree.
            line.setPart(null);
            line.setMatchSource(null);
        }

        line.setStatus(status);
        line.setNotes(request.getNotes());
        line.setChanged(false);
        lineRepository.save(line);

        return toLineDTO(line, project.getInstanceCount(), onHandFor(List.of(line)));
    }

    // ------------------------------------------------------------------
    // Applying
    // ------------------------------------------------------------------

    /**
     * Pushes the matched lines into the project's <b>part list</b> ({@code project_part}) — and, as
     * for any other way a part reaches that list, takes what the build needs out of stock there and
     * then. Lines the shelf cannot cover are allocated short and counted in the result.
     *
     * <p>Kept as a separate, explicit step rather than a side effect of matching: matching is
     * exploratory and half-finished for most of its life, while the part list is what the build
     * runs on. Several BOM lines can resolve to the same part (two 100nF caps in different
     * footprints), so quantities are <b>summed</b> — {@code project_part} is unique per
     * (project, part).
     *
     * <p>Rows already in {@code project_part} that no line accounts for are reported, never
     * deleted: the imported BOM is not the only way parts get into a project.
     */
    @Transactional
    public BomApplyResultDTO apply(Long projectId) {
        Project project = projectService.requireActiveProject(projectId);
        ProjectBom bom = bomRepository.findByProjectId(projectId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "No BOM has been imported for this project"));

        List<ProjectBomLine> lines = lineRepository.findByBomIdWithPart(bom.getId());

        int skippedUnmatched = 0;
        int skippedProvided = 0;
        int skippedExcluded = 0;
        Map<Long, Integer> qtyByPart = new LinkedHashMap<>();
        Map<Long, Part> partsById = new LinkedHashMap<>();

        for (ProjectBomLine line : lines) {
            switch (line.effectiveStatus()) {
                case MATCHED -> {
                    qtyByPart.merge(line.getPart().getId(), line.getQuantity(), Integer::sum);
                    partsById.putIfAbsent(line.getPart().getId(), line.getPart());
                }
                case PROVIDED -> skippedProvided++;
                case EXCLUDED -> skippedExcluded++;
                default -> skippedUnmatched++;
            }
        }

        Map<Long, ProjectPart> existing = projectPartRepository.findByProjectIdWithPart(projectId)
                .stream()
                .collect(Collectors.toMap(pp -> pp.getPart().getId(), pp -> pp, (a, b) -> a,
                        LinkedHashMap::new));

        int created = 0;
        int updated = 0;
        int unchanged = 0;
        int shortParts = 0;
        for (Map.Entry<Long, Integer> entry : qtyByPart.entrySet()) {
            ProjectPart pp = existing.get(entry.getKey());
            if (pp == null) {
                pp = ProjectPart.builder()
                        .project(project)
                        .part(partsById.get(entry.getKey()))
                        .qtyPerInstance(entry.getValue())
                        .qtyAllocated(0)
                        .build();
                created++;
            } else if (pp.getQtyPerInstance() != entry.getValue()) {
                pp.setQtyPerInstance(entry.getValue());
                updated++;
            } else {
                unchanged++;
            }
            // Allocate (or hand back) whatever the new quantity changed, exactly as adding the part
            // by hand would. A line the shelf cannot cover stays on the list, allocated short.
            projectService.syncAllocation(project, pp);
            projectPartRepository.save(pp);
            if (pp.getQtyAllocated() < pp.totalNeeded(project.getInstanceCount())) {
                shortParts++;
            }
        }

        int unaccounted = (int) existing.keySet().stream()
                .filter(partId -> !qtyByPart.containsKey(partId))
                .count();

        return BomApplyResultDTO.builder()
                .created(created)
                .updated(updated)
                .unchanged(unchanged)
                .skippedUnmatched(skippedUnmatched)
                .skippedProvided(skippedProvided)
                .skippedExcluded(skippedExcluded)
                .shortParts(shortParts)
                .unaccountedProjectParts(unaccounted)
                .build();
    }

    /** Drops the imported BOM and every line and decision on it. Leaves the part list alone. */
    @Transactional
    public void delete(Long projectId) {
        projectService.requireOwnProject(projectId);
        bomRepository.findByProjectId(projectId).ifPresent(bomRepository::delete);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private ProjectBomLine requireLine(Long projectId, Long lineId) {
        ProjectBomLine line = lineRepository.findById(lineId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "BOM line not found: " + lineId));
        if (!line.getBom().getProject().getId().equals(projectId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "BOM line not found: " + lineId);
        }
        return line;
    }

    /** The terms worth searching a line on, best first: the MPN, then the schematic value. */
    private Map<String, String> searchTerms(ProjectBomLine line) {
        Map<String, String> terms = new LinkedHashMap<>();
        if (line.getMpn() != null && !line.getMpn().isBlank()) {
            terms.put("mpn", line.getMpn().trim());
        }
        if (line.getValue() != null && !line.getValue().isBlank()) {
            terms.putIfAbsent("value", line.getValue().trim());
        }
        return terms;
    }

    private boolean isExact(PartDTO part, Set<String> terms) {
        return (part.getPartNumber() != null && terms.contains(part.getPartNumber().toLowerCase(Locale.ROOT)))
                || (part.getMpn() != null && terms.contains(part.getMpn().toLowerCase(Locale.ROOT)));
    }

    /** On-hand totals across the organisation for the parts these lines are matched to. */
    private Map<Long, Long> onHandFor(List<ProjectBomLine> lines) {
        List<Long> partIds = lines.stream()
                .map(ProjectBomLine::getPart)
                .filter(Objects::nonNull)
                .map(Part::getId)
                .distinct()
                .collect(Collectors.toList());
        if (partIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Long> onHand = new HashMap<>();
        stockEntryRepository.sumQuantityByPartIdsAndOrganisationId(
                        partIds, currentOrganisationService.currentId())
                .forEach(row -> onHand.put((Long) row[0], (Long) row[1]));
        return onHand;
    }

    private ProjectBomDTO toDTO(Project project, ProjectBom bom, List<ProjectBomLine> lines) {
        Map<Long, Long> onHand = onHandFor(lines);
        Map<BomLineStatus, Long> byStatus = lines.stream()
                .collect(Collectors.groupingBy(ProjectBomLine::effectiveStatus, Collectors.counting()));

        return ProjectBomDTO.builder()
                .id(bom.getId())
                .projectId(project.getId())
                .projectName(project.getName())
                .instanceCount(project.getInstanceCount())
                .canApply(project.getStatus() == ProjectStatus.ACTIVE)
                .filename(bom.getFilename())
                .contentType(bom.getContentType())
                .importedAt(bom.getImportedAt())
                .importedByName(bom.getImportedBy() == null ? null : displayName(bom.getImportedBy()))
                .columnMapping(bom.getColumnMapping())
                .totalLines(lines.size())
                .matchedCount(byStatus.getOrDefault(BomLineStatus.MATCHED, 0L).intValue())
                .unmatchedCount(byStatus.getOrDefault(BomLineStatus.UNMATCHED, 0L).intValue())
                .providedCount(byStatus.getOrDefault(BomLineStatus.PROVIDED, 0L).intValue())
                .excludedCount(byStatus.getOrDefault(BomLineStatus.EXCLUDED, 0L).intValue())
                .changedCount((int) lines.stream().filter(ProjectBomLine::isChanged).count())
                .lines(lines.stream()
                        .map(l -> toLineDTO(l, project.getInstanceCount(), onHand))
                        .collect(Collectors.toList()))
                .build();
    }

    private ProjectBomLineDTO toLineDTO(ProjectBomLine line, int instanceCount, Map<Long, Long> onHand) {
        Part part = line.getPart();
        return ProjectBomLineDTO.builder()
                .id(line.getId())
                .lineNo(line.getLineNo())
                .designators(line.getDesignators())
                .value(line.getValue())
                .footprint(line.getFootprint())
                .mpn(line.getMpn())
                .manufacturer(line.getManufacturer())
                .description(line.getDescription())
                .datasheetUrl(line.getDatasheetUrl())
                .quantity(line.getQuantity())
                .dnp(line.isDnp())
                .extra(line.getExtra())
                .status(line.effectiveStatus())
                .matchSource(line.getMatchSource())
                .changed(line.isChanged())
                .notes(line.getNotes())
                .partId(part == null ? null : part.getId())
                .partNumber(part == null ? null : part.getPartNumber())
                .partDescription(part == null ? null : part.getDescription())
                .onHand(part == null ? null : onHand.getOrDefault(part.getId(), 0L))
                .totalNeeded(line.getQuantity() * instanceCount)
                .build();
    }

    private String displayName(AppUser user) {
        return user.getFullName() != null ? user.getFullName() : user.getEmail();
    }
}
