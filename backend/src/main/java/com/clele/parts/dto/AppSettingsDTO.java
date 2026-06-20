package com.clele.parts.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** App-wide settings exposed to the SPA. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AppSettingsDTO {
    private String currencyCode;
    private String currencySymbol;
}
