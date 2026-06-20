package com.clele.parts.dto;

import com.clele.parts.model.AttachmentType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AttachmentFromUrlRequest {
    @NotBlank(message = "URL is required")
    private String url;

    @NotNull(message = "Type is required")
    private AttachmentType type;
}
