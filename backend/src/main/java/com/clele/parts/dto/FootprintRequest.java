package com.clele.parts.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** Create or change a footprint. {@code aliases} replaces the whole set; null leaves it alone. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FootprintRequest {
    @NotBlank(message = "Footprint name is required")
    @Size(max = 64, message = "At most 64 characters")
    private String name;
    private String description;
    private List<String> aliases;
}
