package com.clele.parts.model;

import jakarta.persistence.*;
import lombok.*;

/** One value a {@link PartKitTemplate} varies over — "10k", "4.7uF", "8 pin". */
@Entity
@Table(name = "part_kit_template_value")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PartKitTemplateValue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "template_id", nullable = false)
    private PartKitTemplate template;

    @Column(name = "value", nullable = false)
    private String value;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;
}
