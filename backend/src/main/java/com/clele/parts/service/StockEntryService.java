package com.clele.parts.service;

import com.clele.parts.dto.StockEntryDTO;
import com.clele.parts.dto.StockEntryRequest;
import com.clele.parts.model.Location;
import com.clele.parts.model.Part;
import com.clele.parts.model.StockEntry;
import com.clele.parts.repository.LocationRepository;
import com.clele.parts.repository.PartRepository;
import com.clele.parts.repository.StockEntryRepository;
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
    private final PartRepository partRepository;
    private final LocationRepository locationRepository;

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
        StockEntry entry = StockEntry.builder()
                .part(part)
                .location(location)
                .quantity(request.getQuantity())
                .minimumQuantity(request.getMinimumQuantity())
                .unitPrice(request.getUnitPrice())
                .build();
        return toDTO(stockEntryRepository.save(entry));
    }

    @Transactional
    public StockEntryDTO update(Long id, StockEntryRequest request) {
        StockEntry entry = stockEntryRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Stock entry not found: " + id));
        if (stockEntryRepository.existsByPartIdAndLocationIdAndIdNot(
                request.getPartId(), request.getLocationId(), id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A stock entry already exists for this part/location combination");
        }
        Part part = partRepository.findById(request.getPartId())
                .orElseThrow(() -> new EntityNotFoundException("Part not found: " + request.getPartId()));
        Location location = locationRepository.findById(request.getLocationId())
                .orElseThrow(() -> new EntityNotFoundException("Location not found: " + request.getLocationId()));
        entry.setPart(part);
        entry.setLocation(location);
        entry.setQuantity(request.getQuantity());
        entry.setMinimumQuantity(request.getMinimumQuantity());
        entry.setUnitPrice(request.getUnitPrice());
        return toDTO(stockEntryRepository.save(entry));
    }

    @Transactional
    public void delete(Long id) {
        StockEntry entry = stockEntryRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Stock entry not found: " + id));
        stockEntryRepository.delete(entry);
    }

    private StockEntryDTO toDTO(StockEntry entry) {
        return StockEntryDTO.builder()
                .id(entry.getId())
                .partId(entry.getPart().getId())
                .partName(entry.getPart().getName())
                .partNumber(entry.getPart().getPartNumber())
                .locationId(entry.getLocation().getId())
                .locationName(entry.getLocation().getName())
                .quantity(entry.getQuantity())
                .minimumQuantity(entry.getMinimumQuantity())
                .lowStock(entry.getQuantity() < entry.getMinimumQuantity())
                .unitPrice(entry.getUnitPrice())
                .build();
    }
}
