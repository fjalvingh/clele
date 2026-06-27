package com.clele.parts.dto;

import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProjectBomEntryDTO {
    private Long id;
    private Long partId;
    private String partName;
    private String partNumber;
    private int qtyPerInstance;
    private int totalNeeded;
    private int pulledTotal;
    private String notes;
}
