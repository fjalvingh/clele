package com.clele.parts.service;

import com.clele.parts.dto.QuickAddRequest;
import com.clele.parts.dto.QuickAddResponseDTO;
import com.clele.parts.dto.StockEntryDTO;
import com.clele.parts.model.Category;
import com.clele.parts.model.Location;
import com.clele.parts.model.Part;
import com.clele.parts.model.StockEntry;
import com.clele.parts.repository.CategoryRepository;
import com.clele.parts.repository.LocationRepository;
import com.clele.parts.repository.PartRepository;
import com.clele.parts.repository.StockEntryRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class QuickAddService {

    private final PartRepository partRepository;
    private final LocationRepository locationRepository;
    private final StockEntryRepository stockEntryRepository;
    private final CategoryRepository categoryRepository;
    private final PartService partService;

    @Transactional
    public QuickAddResponseDTO quickAdd(QuickAddRequest request) {
        // Find or create part
        Part part = partRepository.findByPartNumber(request.getPartNumber())
                .orElseGet(() -> createPart(request));

        // Load location
        Location location = locationRepository.findById(request.getLocationId())
                .orElseThrow(() -> new EntityNotFoundException("Location not found: " + request.getLocationId()));

        // Check for duplicate stock entry
        if (stockEntryRepository.existsByPartIdAndLocationId(part.getId(), location.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A stock entry already exists for this part/location combination");
        }

        StockEntry entry = StockEntry.builder()
                .part(part)
                .location(location)
                .quantity(request.getQuantity())
                .minimumQuantity(request.getMinimumQuantity())
                .unitPrice(request.getUnitPrice())
                .build();
        StockEntry saved = stockEntryRepository.save(entry);

        StockEntryDTO stockEntryDTO = StockEntryDTO.builder()
                .id(saved.getId())
                .partId(part.getId())
                .partName(part.getName())
                .partNumber(part.getPartNumber())
                .locationId(location.getId())
                .locationName(location.getName())
                .quantity(saved.getQuantity())
                .minimumQuantity(saved.getMinimumQuantity())
                .lowStock(saved.getQuantity() < saved.getMinimumQuantity())
                .unitPrice(saved.getUnitPrice())
                .build();

        return new QuickAddResponseDTO(partService.toDTO(part), stockEntryDTO);
    }

    private Part createPart(QuickAddRequest request) {
        Part part = new Part();
        part.setPartNumber(request.getPartNumber());
        part.setName(request.getName());
        part.setDescription(request.getDescription());
        part.setManufacturer(request.getManufacturer());
        part.setDatasheetUrl(request.getDatasheetUrl());
        part.setSpecs(request.getSpecs());
        if (request.getCategoryId() != null) {
            Category category = categoryRepository.findById(request.getCategoryId())
                    .orElseThrow(() -> new EntityNotFoundException("Category not found: " + request.getCategoryId()));
            part.setCategory(category);
        }
        return partRepository.save(part);
    }
}
