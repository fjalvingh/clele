package com.clele.parts.service;

import com.clele.parts.dto.StockMovementDTO;
import com.clele.parts.model.StockMovement;
import com.clele.parts.repository.StockMovementRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StockMovementService {

    private final StockMovementRepository stockMovementRepository;

    public List<StockMovementDTO> findByPartId(Long partId) {
        return stockMovementRepository.findByPartIdOrderByMovedAtDesc(partId).stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
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
                .build();
    }
}
