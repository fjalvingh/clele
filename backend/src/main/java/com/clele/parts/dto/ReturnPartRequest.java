package com.clele.parts.dto;

import jakarta.validation.constraints.Min;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Give some of a project's allocation of one part back to the locations it was taken from. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReturnPartRequest {
    @Min(value = 1, message = "Quantity must be at least 1")
    private int quantity = 1;
}
