package com.clele.parts.dto;

import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PrintDaemonDTO {
    private Long id;
    private String name;
    private String status;
    private String printerIp;
    private Integer tapeWidthMm;
    private boolean owned;
    /** Version the daemon reports; null if it never reported one (pre-versioning build). */
    private String version;
    /** Version this build of the app ships; null when this build doesn't know one. */
    private String expectedVersion;
    /** True only when both versions are known and differ. */
    private boolean outdated;
}

