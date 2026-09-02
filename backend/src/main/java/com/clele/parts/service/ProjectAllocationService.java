package com.clele.parts.service;

import com.clele.parts.model.*;
import com.clele.parts.repository.ProjectStockRepository;
import com.clele.parts.repository.StockEntryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

/**
 * Moves parts between the shelf and a project.
 *
 * <p>An active project physically holds the parts on its part list: adding a part takes it out of
 * stock there and then. This class is the only place that happens, so allocating from the part
 * list, from an applied BOM import, and from reactivating a cancelled project all behave the same.
 *
 * <p><b>Short allocation is a normal outcome, not an error.</b> Every method returns how many it
 * actually moved; the caller stores that as {@code project_part.qty_allocated} and the screen shows
 * the shortfall. Refusing to allocate anything because the shelf is one resistor short would make
 * the project unusable for exactly the case it exists to track.
 */
@Service
@RequiredArgsConstructor
public class ProjectAllocationService {

    private final StockEntryRepository stockEntryRepository;
    private final ProjectStockRepository projectStockRepository;
    private final StockMovementService stockMovementService;
    private final CurrentUserService currentUserService;
    private final CurrentOrganisationService currentOrganisationService;

    /**
     * Takes up to {@code wanted} of a part out of stock and into the project, drawing from the
     * fullest location first so a part spread thin over several drawers is emptied out of as few of
     * them as possible. Each draw is recorded as a {@link ProjectStock} batch remembering its source
     * location, which is where {@link #release} puts it back.
     *
     * @return how many were actually allocated — less than {@code wanted} when stock ran out
     */
    @Transactional
    public int allocate(Project project, Part part, int wanted) {
        if (wanted <= 0) {
            return 0;
        }
        List<StockEntry> entries = stockEntryRepository
                .findByPartIdInAndOrganisationId(List.of(part.getId()),
                        currentOrganisationService.currentId())
                .stream()
                .filter(e -> e.getQuantity() > 0)
                .sorted(Comparator.comparingInt(StockEntry::getQuantity).reversed())
                .toList();

        AppUser me = currentUserService.current();
        int remaining = wanted;
        for (StockEntry entry : entries) {
            if (remaining <= 0) {
                break;
            }
            int take = Math.min(remaining, entry.getQuantity());
            StockMovement movement = stockMovementService.applyForProject(
                    part, entry.getLocation(), -take, entry.getUnitPrice(),
                    "Allocated to project: " + project.getName(),
                    MovementType.PROJECT_OUT, project);
            projectStockRepository.save(ProjectStock.builder()
                    .project(project)
                    .part(part)
                    .location(entry.getLocation())
                    .quantity(take)
                    .unitPrice(entry.getUnitPrice())
                    .movement(movement)
                    .addedAt(LocalDateTime.now())
                    .addedByUser(me)
                    .build());
            remaining -= take;
        }
        return wanted - remaining;
    }

    /**
     * Puts up to {@code wanted} of a part back where it came from, newest batch first. A batch is
     * deleted once it is empty, so {@code project_stock} always describes what the project is still
     * holding.
     *
     * @return how many were actually returned
     */
    @Transactional
    public int release(Project project, Part part, int wanted) {
        if (wanted <= 0) {
            return 0;
        }
        int remaining = wanted;
        for (ProjectStock batch : projectStockRepository
                .findByProjectIdAndPartIdNewestFirst(project.getId(), part.getId())) {
            if (remaining <= 0) {
                break;
            }
            int give = Math.min(remaining, batch.getQuantity());
            stockMovementService.applyForProject(
                    part, batch.getLocation(), give, batch.getUnitPrice(),
                    "Returned from project: " + project.getName(),
                    MovementType.PROJECT_RETURN, project);
            batch.setQuantity(batch.getQuantity() - give);
            if (batch.getQuantity() == 0) {
                projectStockRepository.delete(batch);
            } else {
                projectStockRepository.save(batch);
            }
            remaining -= give;
        }
        return wanted - remaining;
    }
}
