package com.clele.parts.dto;

import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DaemonJobCompleteRequest {
    private String status;
    private String error;
}
