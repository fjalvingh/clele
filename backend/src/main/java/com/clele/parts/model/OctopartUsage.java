package com.clele.parts.model;

import jakarta.persistence.*;
import lombok.*;

/**
 * Per-user, per-month OctoPart (Nexar) request counter. One row per (user, period); {@code period}
 * is the calendar month formatted as {@code YYYY-MM}. Backs the free-contract monthly quota.
 */
@Entity
@Table(name = "octopart_usage",
        uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "period"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OctopartUsage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** Calendar month as 'YYYY-MM'. */
    @Column(nullable = false, length = 7)
    private String period;

    @Column(name = "request_count", nullable = false)
    @Builder.Default
    private int requestCount = 0;
}
