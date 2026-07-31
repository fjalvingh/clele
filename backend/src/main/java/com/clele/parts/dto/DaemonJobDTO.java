package com.clele.parts.dto;

import lombok.*;

/** A job delivered to the daemon via long-poll: the label bytes plus the printer to send them to. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DaemonJobDTO {
    private Long jobId;
    private String labelPngBase64;
    private String printerIp;
}
