package com.clele.parts.dto;

import lombok.*;

/** Current user's OctoPart (Nexar) monthly request usage. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OctopartUsageDTO {
    private int limit;
    private int used;
    private int remaining;
    /** Whether the user has both a client id and secret configured. */
    private boolean hasCredentials;
}
