package com.clele.parts.dto;

import lombok.*;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CategorizationStatusDTO {
    private boolean running;
    private int total;
    private int processed;
    private int assigned;
    private int skipped;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private String lastError;
}
