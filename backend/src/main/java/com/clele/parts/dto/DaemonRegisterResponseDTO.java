package com.clele.parts.dto;

import lombok.*;

/** Returned once, at registration; the raw API key is never retrievable again. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DaemonRegisterResponseDTO {
    private Long daemonId;
    private String apiKey;
}
