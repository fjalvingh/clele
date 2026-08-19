package com.clele.parts.dto;

import lombok.*;

/**
 * Printer configuration for a daemon. The whole configuration is sent on every save, so a field
 * left out is cleared — the UI always submits the full form.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PrintDaemonUpdateRequest {
    private String name;
    /** BROTHER_QL or DYMO_CUPS; null leaves the current type unchanged. */
    private String printerType;
    /** Required for BROTHER_QL. */
    private String printerIp;
    /** Required for DYMO_CUPS: the CUPS destination name on the daemon's machine. */
    private String printerQueue;
    /** Required for DYMO_CUPS: which label stock is loaded, since the printer cannot sense it. */
    private String mediaKeyword;
}
