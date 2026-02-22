package com.clele.parts.controller;

import com.clele.parts.dto.DashboardDTO;
import com.clele.parts.repository.CategoryRepository;
import com.clele.parts.service.LocationService;
import com.clele.parts.service.PartService;
import com.clele.parts.service.StockEntryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
@Tag(name = "Dashboard", description = "Summary statistics")
public class DashboardController {

    private final PartService partService;
    private final LocationService locationService;
    private final StockEntryService stockEntryService;
    private final CategoryRepository categoryRepository;

    @GetMapping
    @Operation(summary = "Get dashboard summary stats")
    public DashboardDTO getDashboard() {
        return DashboardDTO.builder()
                .totalParts(partService.countAll())
                .totalLocations(locationService.countAll())
                .totalCategories(categoryRepository.count())
                .lowStockCount(stockEntryService.countLowStock())
                .build();
    }
}
