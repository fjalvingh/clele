package com.clele.parts.model;

/**
 * Kind of binary content stored in {@code part_attachment}. Photos are PNG-normalized and
 * capped per part; datasheets and attachments keep their original bytes/filename and are uncapped.
 */
public enum AttachmentType {
    PHOTO,
    DATASHEET,
    ATTACHMENT
}
