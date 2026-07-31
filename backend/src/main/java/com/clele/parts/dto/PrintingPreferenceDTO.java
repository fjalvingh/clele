package com.clele.parts.dto;

import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PrintingPreferenceDTO {
    private String printMethod;
    private Long preferredDaemonId;
}
