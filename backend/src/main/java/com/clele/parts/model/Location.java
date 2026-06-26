package com.clele.parts.model;

import jakarta.persistence.*;
import lombok.*;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "location")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Location {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    /** Parent location in the storage hierarchy (e.g. a room inside a building). NULL = root. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private Location parent;

    @OneToMany(mappedBy = "parent", fetch = FetchType.LAZY)
    @Builder.Default
    private List<Location> children = new ArrayList<>();

    /** The user who owns this location; stock stored here belongs to this user. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id", nullable = false)
    private AppUser owner;

    /** Full path from the root location, e.g. "Building A > Room B > Cupboard C". */
    public String breadcrumb() {
        ArrayDeque<String> parts = new ArrayDeque<>();
        Location current = this;
        while (current != null) {
            parts.addFirst(current.getName());
            current = current.getParent();
        }
        return String.join(" > ", parts);
    }
}
