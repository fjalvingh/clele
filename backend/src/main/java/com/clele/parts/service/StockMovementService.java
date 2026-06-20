package com.clele.parts.service;

import com.clele.parts.dto.StockMovementDTO;
import com.clele.parts.model.AppUser;
import com.clele.parts.model.Location;
import com.clele.parts.model.MovementType;
import com.clele.parts.model.Part;
import com.clele.parts.model.StockEntry;
import com.clele.parts.model.StockMovement;
import com.clele.parts.repository.StockEntryRepository;
import com.clele.parts.repository.StockMovementRepository;
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
     * The single funnel for every on-hand change. Records a signed-delta {@link StockMovement}
     * (the source of truth) and keeps the {@link StockEntry} aggregate in step, so the invariant
     * {@code stock_entry.quantity == Σ stock_movement.quantity} always holds.
     *
     * @return the updated (or newly created) stock entry
     */
    @Transactional
    public StockEntry apply(Part part, Location location, int deltaQty, BigDecimal unitPrice,
                            String comments, MovementType type) {
        requireOwnLocation(location);

        StockEntry entry = stockEntryRepository
                .findByPartIdAndLocationId(part.getId(), location.getId())
                .orElseGet(() -> StockEntry.builder()
                        .part(part)
                        .location(location)
                        .quantity(0)
                        .minimumQuantity(0)
                        .build());

        int newQuantity = entry.getQuantity() + deltaQty;
        if (newQuantity < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Stock would go negative at this location");
        }

        AppUser me = currentUserService.current();
        stockMovementRepository.save(StockMovement.builder()
                .part(part)
                .location(location)
                .quantity(deltaQty)
                .unitPrice(unitPrice)
                .comments(comments)
                .type(type)
                .movedAt(LocalDateTime.now())
                .createdBy(me.getFullName() != null ? me.getFullName() : me.getEmail())
                .build());

        entry.setQuantity(newQuantity);
        if (unitPrice != null) {
            entry.setUnitPrice(unitPrice);
        }
        return stockEntryRepository.save(entry);
    }

    /** Stock may only be changed in a location the current user owns. */
    public void requireOwnLocation(Location location) {
        AppUser me = currentUserService.current();
        if (location.getOwner() == null || !location.getOwner().getId().equals(me.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "You can only change stock in your own locations");
        }
    }

    private StockMovementDTO toDTO(StockMovement m) {
        return StockMovementDTO.builder()
                .id(m.getId())
                .partId(m.getPart().getId())
                .locationId(m.getLocation().getId())
                .locationName(m.getLocation().getName())
                .quantity(m.getQuantity())
                .unitPrice(m.getUnitPrice())
                .currency(m.getCurrency())
                .comments(m.getComments())
                .movedAt(m.getMovedAt())
                .createdBy(m.getCreatedBy())
                .type(m.getType())
                .build();
    }
}
