package com.clele.parts.dto;

import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PrintingPreferenceRequest {
    private String printMethod;
    private Long preferredDaemonId;
    private Boolean printBarcodeLabel;
}
