package com.clele.parts.repository;

import com.clele.parts.dto.UserDashboardDTO;
import com.clele.parts.model.Location;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface LocationRepository extends JpaRepository<Location, Long> {

    Optional<Location> findByName(String name);

    List<Location> findByOwnerIdOrderByName(Long ownerId);

    boolean existsByOwnerIdAndName(Long ownerId, String name);

    boolean existsByOwnerIdAndNameAndIdNot(Long ownerId, String name, Long id);

    /**
     * Per-owner roll-up of the stock held in the locations each user owns: location count,
     * distinct parts, total on-hand quantity, total stock value, and low-stock entries.
     * Users that own at least one location appear (even with no stock).
     */
    @Query("""
            SELECT new com.clele.parts.dto.UserDashboardDTO(
                o.id,
                COALESCE(o.fullName, o.email),
                COUNT(DISTINCT l.id),
                COUNT(DISTINCT s.part.id),
                COALESCE(SUM(s.quantity), 0L),
                COALESCE(SUM(CASE WHEN s.unitPrice IS NOT NULL THEN s.quantity * s.unitPrice ELSE 0 END), 0),
                COALESCE(SUM(CASE WHEN s.quantity < s.minimumQuantity THEN 1L ELSE 0L END), 0L))
            FROM Location l
            JOIN l.owner o
            LEFT JOIN StockEntry s ON s.location = l
            GROUP BY o.id, o.fullName, o.email
            ORDER BY COALESCE(o.fullName, o.email)
            """)
    List<UserDashboardDTO> perUserStats();
}
