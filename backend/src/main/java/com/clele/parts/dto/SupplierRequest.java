package com.clele.parts.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SupplierRequest {
    @NotBlank(message = "Supplier name is required")
    @Size(max = 100, message = "At most 100 characters")
    private String name;
}
