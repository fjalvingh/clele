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
    /** The part number of the first part this attachment was used for; never changes. */
    private String description;
    /** MD5 of the stored bytes, hex. */
    private String md5Hash;
    /** How many parts use this attachment — 1 unless it is shared. */
    private Integer partCount;
    private LocalDateTime createdAt;
}
