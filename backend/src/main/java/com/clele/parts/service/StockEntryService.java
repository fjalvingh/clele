package com.clele.parts.service;

import com.clele.parts.dto.StockAdjustRequest;
import com.clele.parts.dto.StockEntryDTO;
import com.clele.parts.dto.StockEntryRequest;
import com.clele.parts.dto.StockMoveRequest;
import com.clele.parts.model.Location;
import com.clele.parts.model.MovementType;
import com.clele.parts.model.Part;
import com.clele.parts.model.StockEntry;
import com.clele.parts.repository.LocationRepository;
import com.clele.parts.repository.PartRepository;
import com.clele.parts.repository.StockEntryRepository;
import com.clele.parts.repository.StockMovementRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StockEntryService {

    private final StockEntryRepository stockEntryRepository;
    private final StockMovementRepository stockMovementRepository;
    private final PartRepository partRepository;
    private final LocationRepository locationRepository;
    private final StockMovementService stockMovementService;
    private final CurrentUserService currentUserService;
    private final CurrentOrganisationService currentOrganisationService;

    public List<StockEntryDTO> findAll() {
        return stockEntryRepository.findByOrganisationId(currentOrganisationService.currentId()).stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    public StockEntryDTO findById(Long id) {
        return toDTO(requireEntry(id));
    }

    public List<StockEntryDTO> findByPartId(Long partId) {
        requirePart(partId);
        return stockEntryRepository.findByPartId(partId).stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    public java.math.BigDecimal totalStockValue() {
        return stockEntryRepository.totalStockValue(currentOrganisationService.currentId());
    }

    @Transactional
    public StockEntryDTO create(StockEntryRequest request) {
        if (stockEntryRepository.existsByPartIdAndLocationId(request.getPartId(), request.getLocationId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A stock entry already exists for this part/location combination");
        }
        Part part = requirePart(request.getPartId());
        Location location = requireLocation(request.getLocationId());
        // The funnel writes the INITIAL movement, creates the entry and checks location ownership.
        StockEntry entry = stockMovementService.apply(part, location, request.getQuantity(),
                request.getUnitPrice(), request.getComments(), MovementType.INITIAL);
        StockEntryDTO dto = toDTO(stockEntryRepository.save(entry));
        currentUserService.rememberLastLocation(location);
        return dto;
    }

    /**
     * Add a (positive) quantity of stock at a location, creating the entry if needed. Also (re)sets
     * the low-stock threshold and unit price when supplied. Records a {@code PURCHASE} movement.
     */
    @Transactional
    public StockEntryDTO addStock(StockAdjustRequest request) {
        Part part = requirePart(request.getPartId());
        Location location = requireLocation(request.getLocationId());
        StockEntry entry = stockMovementService.apply(part, location, request.getQuantity(),
                request.getUnitPrice(), request.getComments(), MovementType.PURCHASE);
        StockEntryDTO dto = toDTO(stockEntryRepository.save(entry));
        currentUserService.rememberLastLocation(location);
        return dto;
    }

    /** Take a (positive) quantity of stock from a location. Records a {@code CONSUME} movement. */
    @Transactional
    public StockEntryDTO takeStock(StockAdjustRequest request) {
        Part part = requirePart(request.getPartId());
        Location location = requireLocation(request.getLocationId());
        StockEntry entry = stockMovementService.apply(part, location, -request.getQuantity(),
                null, request.getComments(), MovementType.CONSUME);
        return toDTO(stockEntryRepository.save(entry));
    }

    /**
     * Move stock between two locations of the current organisation. Records a single atomic MOVE
     * movement.
     */
    @Transactional
    public void move(StockMoveRequest request) {
        if (request.getFromLocationId().equals(request.getToLocationId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Source and destination locations must be different");
        }
        Part part = requirePart(request.getPartId());
        Location from = requireLocation(request.getFromLocationId());
        Location to = requireLocation(request.getToLocationId());
        int qty = request.getQuantity();
        stockMovementService.applyMove(part, from, to, qty,
                request.getComments() != null && !request.getComments().isBlank()
                        ? request.getComments().trim() : null);
    }

    /** Parts and locations are only reachable within the organisation currently in force. */
    private Part requirePart(Long id) {
        return partRepository.findByIdAndOrganisationId(id, currentOrganisationService.currentId())
                .orElseThrow(() -> new EntityNotFoundException("Part not found: " + id));
    }

    private Location requireLocation(Long id) {
        return locationRepository.findByIdAndOrganisationId(id, currentOrganisationService.currentId())
                .orElseThrow(() -> new EntityNotFoundException("Location not found: " + id));
    }

    /** A stock entry is reachable only when its location is in the current organisation. */
    private StockEntry requireEntry(Long id) {
        StockEntry entry = stockEntryRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Stock entry not found: " + id));
        requireLocation(entry.getLocation().getId());
        return entry;
    }

    @Transactional
    public StockEntryDTO update(Long id, StockEntryRequest request) {
        StockEntry entry = requireEntry(id);
        // Quantity changes flow through the ledger; part/location of an existing entry are fixed.
        Part part = entry.getPart();
        Location location = entry.getLocation();
        int delta = request.getQuantity() - entry.getQuantity();
        if (delta != 0) {
            entry = stockMovementService.apply(part, location, delta,
                    request.getUnitPrice(), request.getComments(), MovementType.ADJUST);
        } else {
            // No quantity change, but still gate on the organisation and allow a price edit.
            stockMovementService.requireCurrentOrganisation(location);
            if (request.getUnitPrice() != null) {
                entry.setUnitPrice(request.getUnitPrice());
            }
        }
        return toDTO(stockEntryRepository.save(entry));
    }

    @Transactional
    public void delete(Long id) {
        StockEntry entry = requireEntry(id);
        stockMovementService.requireCurrentOrganisation(entry.getLocation());
        // Record the removal in the ledger so history stays complete, then drop the aggregate row.
        if (entry.getQuantity() != 0) {
            stockMovementService.apply(entry.getPart(), entry.getLocation(), -entry.getQuantity(),
                    null, "Stock entry removed", MovementType.ADJUST);
        }
        stockEntryRepository.delete(entry);
    }

    /**
     * Realign every aggregate to its ledger (invariant safety net / verification hook).
     * @return the number of entries that were corrected
     */
    @Transactional
    public int reconcile() {
        int corrected = 0;
        for (StockEntry entry : stockEntryRepository
                .findByOrganisationId(currentOrganisationService.currentId())) {
            int sum = stockMovementRepository.sumQuantity(entry.getPart().getId(), entry.getLocation().getId());
            if (entry.getQuantity() != sum) {
                entry.setQuantity(sum);
                stockEntryRepository.save(entry);
                corrected++;
            }
        }
        return corrected;
    }

    private StockEntryDTO toDTO(StockEntry entry) {
        return StockEntryDTO.builder()
                .id(entry.getId())
                .partId(entry.getPart().getId())
                .partName(entry.getPart().getPartNumber())
                .partNumber(entry.getPart().getPartNumber())
                .locationId(entry.getLocation().getId())
                .locationName(entry.getLocation().getName())
                .locationBreadcrumb(entry.getLocation().breadcrumb())
                .quantity(entry.getQuantity())
                .unitPrice(entry.getUnitPrice())
                .build();
    }
}
