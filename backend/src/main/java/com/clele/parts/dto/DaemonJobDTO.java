package com.clele.parts.dto;

import lombok.*;

/**
 * A job delivered to the daemon via long-poll: the label bytes plus the printer to send them to.
 *
 * <p>The printer configuration travels on the job as well as on the poll headers, so a
 * configuration change racing a queued job cannot make the daemon print it with settings the label
 * was not formatted for.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DaemonJobDTO {
    private Long jobId;
    private String labelPngBase64;
    private String printerType;
    private String printerIp;
    private String printerQueue;
    private String mediaKeyword;
}
