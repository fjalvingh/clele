package com.clele.parts.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.util.List;

/** Move a set of spec definitions into another group. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MoveSpecsRequest {

    @NotEmpty(message = "At least one spec is required")
    private List<Long> specIds;

    @NotNull(message = "Target group is required")
    private Long groupId;
}
