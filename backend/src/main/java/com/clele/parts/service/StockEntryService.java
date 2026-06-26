package com.clele.parts.service;

import com.clele.parts.dto.StockEntryDTO;
import com.clele.parts.dto.StockEntryRequest;
import com.clele.parts.model.AppUser;
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

    public List<StockEntryDTO> findAll() {
        return stockEntryRepository.findAll().stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    public StockEntryDTO findById(Long id) {
        return toDTO(stockEntryRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Stock entry not found: " + id)));
    }

    public List<StockEntryDTO> findByPartId(Long partId) {
        return stockEntryRepository.findByPartId(partId).stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    public List<StockEntryDTO> findLowStock() {
        return stockEntryRepository.findLowStock().stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    public long countLowStock() {
        return stockEntryRepository.countLowStock();
    }

    public java.math.BigDecimal totalStockValue() {
        return stockEntryRepository.totalStockValue();
    }

    @Transactional
    public StockEntryDTO create(StockEntryRequest request) {
        if (stockEntryRepository.existsByPartIdAndLocationId(request.getPartId(), request.getLocationId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A stock entry already exists for this part/location combination");
        }
        Part part = partRepository.findById(request.getPartId())
                .orElseThrow(() -> new EntityNotFoundException("Part not found: " + request.getPartId()));
        Location location = locationRepository.findById(request.getLocationId())
                .orElseThrow(() -> new EntityNotFoundException("Location not found: " + request.getLocationId()));
        // The funnel writes the INITIAL movement, creates the entry and checks location ownership.
        StockEntry entry = stockMovementService.apply(part, location, request.getQuantity(),
                request.getUnitPrice(), request.getComments(), MovementType.INITIAL);
        entry.setMinimumQuantity(request.getMinimumQuantity());
        StockEntryDTO dto = toDTO(stockEntryRepository.save(entry));
        currentUserService.rememberLastLocation(location);
        return dto;
    }

    @Transactional
    public StockEntryDTO update(Long id, StockEntryRequest request) {
        StockEntry entry = stockEntryRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Stock entry not found: " + id));
        // Quantity changes flow through the ledger; part/location of an existing entry are fixed.
        Part part = entry.getPart();
        Location location = entry.getLocation();
        int delta = request.getQuantity() - entry.getQuantity();
        if (delta != 0) {
            entry = stockMovementService.apply(part, location, delta,
                    request.getUnitPrice(), request.getComments(), MovementType.ADJUST);
        } else {
            // No quantity change, but still gate on ownership and allow a price edit.
            stockMovementService.requireOwnLocation(location);
            if (request.getUnitPrice() != null) {
                entry.setUnitPrice(request.getUnitPrice());
            }
        }
        entry.setMinimumQuantity(request.getMinimumQuantity());
        return toDTO(stockEntryRepository.save(entry));
    }

    @Transactional
    public void delete(Long id) {
        StockEntry entry = stockEntryRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Stock entry not found: " + id));
        stockMovementService.requireOwnLocation(entry.getLocation());
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
        for (StockEntry entry : stockEntryRepository.findAll()) {
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
        AppUser owner = entry.getLocation().getOwner();
        return StockEntryDTO.builder()
                .id(entry.getId())
                .partId(entry.getPart().getId())
                .partName(entry.getPart().getName())
                .partNumber(entry.getPart().getPartNumber())
                .locationId(entry.getLocation().getId())
                .locationName(entry.getLocation().getName())
                .locationBreadcrumb(entry.getLocation().breadcrumb())
                .ownerName(owner != null ? (owner.getFullName() != null ? owner.getFullName() : owner.getEmail()) : null)
                .quantity(entry.getQuantity())
                .minimumQuantity(entry.getMinimumQuantity())
                .lowStock(entry.getQuantity() < entry.getMinimumQuantity())
                .unitPrice(entry.getUnitPrice())
                .build();
    }
}
