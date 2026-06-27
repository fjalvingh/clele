package com.clele.parts.service;

import com.clele.parts.dto.StockMovementDTO;
import com.clele.parts.model.AppUser;
import com.clele.parts.model.Location;
import com.clele.parts.model.MovementType;
import com.clele.parts.model.Part;
import com.clele.parts.model.Project;
import com.clele.parts.model.StockEntry;
import com.clele.parts.model.StockMovement;
import com.clele.parts.repository.StockEntryRepository;
import com.clele.parts.repository.StockMovementRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.util.Pair;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StockMovementService {

    private final StockMovementRepository stockMovementRepository;
    private final StockEntryRepository stockEntryRepository;
    private final CurrentUserService currentUserService;

    public List<StockMovementDTO> findByPartId(Long partId) {
        return stockMovementRepository.findByPartIdOrderByMovedAtDesc(partId).stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    /**
     * The single funnel for every on-hand change (non-MOVE). Records a signed-delta
     * {@link StockMovement} and keeps the {@link StockEntry} aggregate in step, so the invariant
     * {@code stock_entry.quantity == Σ stock_movement.quantity} always holds.
     *
     * @return the updated (or newly created) stock entry
     */
    @Transactional
    public StockEntry apply(Part part, Location location, int deltaQty, BigDecimal unitPrice,
                            String comments, MovementType type) {
        requireOwnLocation(location);
        return applyInternal(part, location, deltaQty, unitPrice, comments, type, null).getSecond();
    }

    /**
     * Same as {@link #apply} but skips the own-location guard. Used for non-MOVE adjustments
     * where the caller has already verified ownership (or it is intentionally waived).
     */
    @Transactional
    public StockEntry applyNoOwnershipCheck(Part part, Location location, int deltaQty,
                                            BigDecimal unitPrice, String comments, MovementType type) {
        return applyInternal(part, location, deltaQty, unitPrice, comments, type, null).getSecond();
    }

    /**
     * Project-aware apply: same as {@link #apply} but sets {@code movement.project} so the ledger
     * row links directly to the project by FK. Returns the saved movement (for storing its id in
     * {@code project_stock}).
     */
    @Transactional
    public StockMovement applyForProject(Part part, Location location, int deltaQty,
                                         BigDecimal unitPrice, String comments,
                                         MovementType type, Project project) {
        requireOwnLocation(location);
        return applyInternal(part, location, deltaQty, unitPrice, comments, type, project).getFirst();
    }

    /**
     * Atomic stock move: debit {@code qty} from {@code from} and credit it to {@code to} in a
     * single {@link StockMovement} row (type=MOVE, quantity=-qty at source, targetLocation=to).
     * The source must be owned by the current user; the destination may be any user's location.
     *
     * @return the updated source stock entry
     */
    @Transactional
    public StockEntry applyMove(Part part, Location from, Location to, int qty, String comments) {
        requireOwnLocation(from);

        // Debit the source entry.
        StockEntry srcEntry = stockEntryRepository
                .findByPartIdAndLocationId(part.getId(), from.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "No stock at source location"));
        int newSrcQty = srcEntry.getQuantity() - qty;
        if (newSrcQty < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Stock would go negative at this location");
        }
        BigDecimal sourceWac = srcEntry.getUnitPrice();
        srcEntry.setQuantity(newSrcQty);
        stockEntryRepository.save(srcEntry);

        // Credit the destination entry (create if needed), merging WAC.
        StockEntry dstEntry = stockEntryRepository
                .findByPartIdAndLocationId(part.getId(), to.getId())
                .orElseGet(() -> StockEntry.builder()
                        .part(part)
                        .location(to)
                        .quantity(0)
                        .build());
        int oldDstQty = dstEntry.getQuantity();
        dstEntry.setQuantity(oldDstQty + qty);
        if (sourceWac != null) {
            if (oldDstQty == 0 || dstEntry.getUnitPrice() == null) {
                dstEntry.setUnitPrice(sourceWac);
            } else {
                // Weighted average of existing destination stock and incoming stock.
                BigDecimal merged = dstEntry.getUnitPrice().multiply(BigDecimal.valueOf(oldDstQty))
                        .add(sourceWac.multiply(BigDecimal.valueOf(qty)))
                        .divide(BigDecimal.valueOf(oldDstQty + qty), 10, RoundingMode.HALF_UP);
                dstEntry.setUnitPrice(merged.setScale(2, RoundingMode.HALF_UP));
            }
        }
        stockEntryRepository.save(dstEntry);

        // Write a single MOVE record: quantity=-qty (debit from source), targetLocation=to.
        AppUser me = currentUserService.current();
        stockMovementRepository.save(StockMovement.builder()
                .part(part)
                .location(from)
                .targetLocation(to)
                .quantity(-qty)
                .unitPrice(sourceWac)
                .comments(comments)
                .type(MovementType.MOVE)
                .movedAt(LocalDateTime.now())
                .createdBy(me.getFullName() != null ? me.getFullName() : me.getEmail())
                .build());

        return srcEntry;
    }

    /** Stock may only be changed in a location the current user owns. */
    public void requireOwnLocation(Location location) {
        AppUser me = currentUserService.current();
        if (location.getOwner() == null || !location.getOwner().getId().equals(me.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "You can only change stock in your own locations");
        }
    }

    // ------------------------------------------------------------------
    // Internal
    // ------------------------------------------------------------------

    /**
     * Core apply logic shared by the public variants. Returns {@code Pair<movement, entry>} so
     * callers can choose which to return to their own callers.
     */
    private Pair<StockMovement, StockEntry> applyInternal(Part part, Location location, int deltaQty,
                                                           BigDecimal unitPrice, String comments,
                                                           MovementType type, Project project) {
        StockEntry entry = stockEntryRepository
                .findByPartIdAndLocationId(part.getId(), location.getId())
                .orElseGet(() -> StockEntry.builder()
                        .part(part)
                        .location(location)
                        .quantity(0)
                        .build());

        int newQuantity = entry.getQuantity() + deltaQty;
        if (newQuantity < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Stock would go negative at this location");
        }

        // For CONSUME/PROJECT_OUT movements record the WAC at time of consumption.
        BigDecimal movementPrice = (deltaQty < 0 && unitPrice == null)
                ? entry.getUnitPrice()
                : unitPrice;

        AppUser me = currentUserService.current();
        StockMovement movement = stockMovementRepository.save(StockMovement.builder()
                .part(part)
                .location(location)
                .quantity(deltaQty)
                .unitPrice(movementPrice)
                .comments(comments)
                .type(type)
                .movedAt(LocalDateTime.now())
                .createdBy(me.getFullName() != null ? me.getFullName() : me.getEmail())
                .project(project)
                .build());

        entry.setQuantity(newQuantity);
        if (unitPrice != null && deltaQty > 0 && newQuantity > 0) {
            // Recalculate weighted average cost: (old_qty × old_wac + added_qty × price) / new_qty
            int oldQty = newQuantity - deltaQty;
            BigDecimal oldWac = entry.getUnitPrice() != null ? entry.getUnitPrice() : BigDecimal.ZERO;
            BigDecimal newWac = oldWac.multiply(BigDecimal.valueOf(oldQty))
                    .add(unitPrice.multiply(BigDecimal.valueOf(deltaQty)))
                    .divide(BigDecimal.valueOf(newQuantity), 10, RoundingMode.HALF_UP);
            entry.setUnitPrice(newWac.setScale(2, RoundingMode.HALF_UP));
        }
        StockEntry saved = stockEntryRepository.save(entry);
        return Pair.of(movement, saved);
    }

    private StockMovementDTO toDTO(StockMovement m) {
        StockMovementDTO.StockMovementDTOBuilder b = StockMovementDTO.builder()
                .id(m.getId())
                .partId(m.getPart().getId())
                .locationId(m.getLocation().getId())
                .locationName(m.getLocation().getName())
                .locationBreadcrumb(m.getLocation().breadcrumb())
                .quantity(m.getQuantity())
                .unitPrice(m.getUnitPrice())
                .comments(m.getComments())
                .movedAt(m.getMovedAt())
                .createdBy(m.getCreatedBy())
                .type(m.getType());

        if (m.getTargetLocation() != null) {
            b.targetLocationId(m.getTargetLocation().getId())
             .targetLocationName(m.getTargetLocation().getName())
             .targetLocationBreadcrumb(m.getTargetLocation().breadcrumb());
        }

        if (m.getProject() != null) {
            b.projectId(m.getProject().getId())
             .projectName(m.getProject().getName());
        }

        return b.build();
    }
}
