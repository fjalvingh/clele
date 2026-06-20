package com.clele.parts.dto;

import com.clele.parts.model.AttachmentType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PartAttachmentDTO {
    private Long id;
    private Long partId;
    private AttachmentType type;
    private Integer displayOrder;
    private String contentType;
    private String filename;
    private LocalDateTime createdAt;
}
