package com.clele.parts.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class QuickAddResponseDTO {
    private PartDTO part;
    private StockEntryDTO stockEntry;
}
