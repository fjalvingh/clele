package com.clele.parts.dto;

import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PrintDaemonUpdateRequest {
    private String name;
    private String printerIp;
}
