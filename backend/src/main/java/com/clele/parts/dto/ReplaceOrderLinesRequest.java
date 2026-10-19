package com.clele.parts.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** The complete new set of lines for a draft order — usually what the supplier's file said. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReplaceOrderLinesRequest {
    @NotEmpty(message = "An order needs at least one line")
    private List<@Valid PurchaseOrderLineRequest> lines;
}
