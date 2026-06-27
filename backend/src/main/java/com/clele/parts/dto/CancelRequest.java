package com.clele.parts.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CancelRequest {
    /** IDs of project_stock rows to return to their original source locations. May be empty. */
    private List<Long> returnStockIds;
}
