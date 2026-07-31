package com.clele.parts.dto;

import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PrintJobDTO {
    private Long id;
    private String status;
    private String errorMessage;
}
