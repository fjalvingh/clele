package com.clele.parts.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PartImageDTO {
    private Long id;
    private Long partId;
    private Integer displayOrder;
    private LocalDateTime createdAt;
}
