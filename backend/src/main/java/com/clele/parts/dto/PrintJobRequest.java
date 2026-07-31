package com.clele.parts.dto;

import lombok.*;

/** Base64-encoded label PNG bytes plus the target daemon; used by the browser to enqueue a job. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PrintJobRequest {
    private Long daemonId;
    private String labelPngBase64;
}
