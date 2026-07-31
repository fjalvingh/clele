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
    /** Media detected in the printer over IPP; null until the daemon has read it. */
    private String mediaKind;
    private Integer mediaWidthMm;
    private Integer mediaLengthMm;
    private String mediaName;
    /** Human-readable media summary for the UI, e.g. "17 × 54 mm die-cut labels". */
    private String mediaDescription;
    private boolean owned;
    /** Version the daemon reports; null if it never reported one (pre-versioning build). */
    private String version;
    /** Version this build of the app ships; null when this build doesn't know one. */
    private String expectedVersion;
    /** True only when both versions are known and differ. */
    private boolean outdated;
}

