package com.clele.parts.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** Order list items to put on an existing draft order. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrderItemIdsRequest {
    @NotEmpty(message = "Select at least one item")
    private List<Long> itemIds;
}
