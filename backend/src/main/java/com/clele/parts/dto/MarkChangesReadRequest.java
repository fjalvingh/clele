package com.clele.parts.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MarkChangesReadRequest {
    /** 8-digit date of the newest changelog entry the user has now read. */
    private String date;
}
