package com.clele.parts.service;

import com.clele.parts.dto.StockThresholdDTO;
import com.clele.parts.dto.StockThresholdRequest;
import com.clele.parts.model.Location;
import com.clele.parts.model.Part;
import com.clele.parts.model.StockThreshold;
import com.clele.parts.repository.LocationRepository;
import com.clele.parts.repository.PartRepository;
import com.clele.parts.repository.StockThresholdRepository;
import com.clele.parts.repository.StockThresholdView;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StockThresholdService {

    private final StockThresholdRepository thresholdRepository;
    private final PartRepository partRepository;
    private final LocationRepository locationRepository;

    public List<StockThresholdDTO> findAll(Long partId) {
        List<StockThresholdView> rows = partId != null
                ? thresholdRepository.findByPartIdWithTotals(partId)
                : thresholdRepository.findAllWithTotals();
        return rows.stream().map(this::toDTO).toList();
    }

    public List<StockThresholdDTO> findLowStock() {
        return thresholdRepository.findLowStock().stream().map(this::toDTO).toList();
    }

    public long countLowStock() {
        return thresholdRepository.countLowStock();
    }

    @Transactional
    public StockThresholdDTO upsert(StockThresholdRequest request) {
        Location location = locationRepository.findById(request.getLocationId())
                .orElseThrow(() -> new EntityNotFoundException("Location not found: " + request.getLocationId()));
        if (location.getParent() != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Stock thresholds can only be set on root locations (no parent)");
        }
        Part part = partRepository.findById(request.getPartId())
                .orElseThrow(() -> new EntityNotFoundException("Part not found: " + request.getPartId()));

        StockThreshold threshold = thresholdRepository
                .findByPartIdAndLocationId(part.getId(), location.getId())
                .orElseGet(() -> StockThreshold.builder().part(part).location(location).build());
        threshold.setMinimumQuantity(request.getMinimumQuantity());
        StockThreshold saved = thresholdRepository.save(threshold);

        // Re-query with totals so the response includes the current subtree total.
        return thresholdRepository.findByPartIdWithTotals(part.getId()).stream()
                .filter(v -> v.getId().equals(saved.getId()))
                .map(this::toDTO)
                .findFirst()
                .orElseGet(() -> simpleDTO(saved));
    }

    @Transactional
    public void delete(Long id) {
        StockThreshold threshold = thresholdRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Stock threshold not found: " + id));
        thresholdRepository.delete(threshold);
    }

    private StockThresholdDTO toDTO(StockThresholdView v) {
        return StockThresholdDTO.builder()
                .id(v.getId())
                .partId(v.getPartId())
                .partName(v.getPartName())
                .partNumber(v.getPartNumber())
                .locationId(v.getLocationId())
                .locationName(v.getLocationName())
                .minimumQuantity(v.getMinimumQuantity())
                .totalQuantity(v.getTotalQuantity())
                .lowStock(v.getTotalQuantity() < v.getMinimumQuantity())
                .build();
    }

    private StockThresholdDTO simpleDTO(StockThreshold t) {
        return StockThresholdDTO.builder()
                .id(t.getId())
                .partId(t.getPart().getId())
                .partName(t.getPart().getPartNumber())
                .partNumber(t.getPart().getPartNumber())
                .locationId(t.getLocation().getId())
                .locationName(t.getLocation().getName())
                .minimumQuantity(t.getMinimumQuantity())
                .totalQuantity(0L)
                .lowStock(t.getMinimumQuantity() > 0)
                .build();
    }
}
