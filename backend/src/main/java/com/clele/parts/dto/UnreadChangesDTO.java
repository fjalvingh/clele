package com.clele.parts.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UnreadChangesDTO {
    /** Merged HTML content of all unread changelog entries, ready to render. */
    private String html;
    /** 8-digit date string of the newest unread entry (e.g. "20260623"). Null if count == 0. */
    private String latestDate;
    /** Number of unread changelog entries. */
    private int count;
}
