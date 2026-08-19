package com.clele.parts.dto;

import com.clele.parts.model.DaemonCapabilities;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PrintDaemonDTO {
    private Long id;
    private String name;
    private String status;
    /** Printer family: decides whether printerIp or printerQueue + mediaKeyword apply. */
    private String printerType;
    private String printerIp;
    private String printerQueue;
    /** Label size the user picked, for a printer that cannot sense its own roll. */
    private String mediaKeyword;
    /** Model the printer reports over IPP, e.g. "DYMO LabelWriter 320". */
    private String printerModel;
    /** Media in the printer: detected for a Brother, resolved from mediaKeyword for a Dymo. */
    private String mediaKind;
    private BigDecimal mediaWidthMm;
    private BigDecimal mediaLengthMm;
    private String mediaName;
    /** Human-readable media summary for the UI, e.g. "17 × 54 mm die-cut labels". */
    private String mediaDescription;
    /**
     * The area the printer can actually mark, as the daemon reports it. The frontend renders
     * labels to exactly this, instead of subtracting printer constants it cannot verify.
     */
    private BigDecimal printableWidthMm;
    private BigDecimal printableLengthMm;
    /** Queues and label sizes the daemon found locally; null until it has reported them. */
    private DaemonCapabilities capabilities;
    private LocalDateTime capabilitiesReportedAt;
    private boolean owned;
    /** Version the daemon reports; null if it never reported one (pre-versioning build). */
    private String version;
    /** Version this build of the app ships; null when this build doesn't know one. */
    private String expectedVersion;
    /** True only when both versions are known and differ. */
    private boolean outdated;
}
