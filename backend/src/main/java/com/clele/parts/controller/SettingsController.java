package com.clele.parts.controller;

import com.clele.parts.config.AppProperties;
import com.clele.parts.dto.AppSettingsDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/settings")
@RequiredArgsConstructor
@Tag(name = "Settings", description = "App-wide settings")
public class SettingsController {

    private final AppProperties appProperties;

    @GetMapping
    @Operation(summary = "Get app-wide settings (currency, …)")
    public AppSettingsDTO get() {
        return new AppSettingsDTO(
                appProperties.getCurrency().getCode(),
                appProperties.getCurrency().getSymbol());
    }
}
