package com.clele.parts.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "part_image")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PartImage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "part_id", nullable = false)
    private Part part;

    @Column(name = "display_order", nullable = false)
    private Integer displayOrder;

    @Column(name = "image_data", nullable = false, columnDefinition = "bytea")
    private byte[] imageData;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
