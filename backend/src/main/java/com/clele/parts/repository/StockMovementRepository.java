package com.clele.parts.repository;

import com.clele.parts.model.Location;
import com.clele.parts.model.StockMovement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface StockMovementRepository extends JpaRepository<StockMovement, Long> {

    @Query("SELECT m FROM StockMovement m JOIN FETCH m.location LEFT JOIN FETCH m.targetLocation LEFT JOIN FETCH m.project WHERE m.part.id = :partId ORDER BY m.movedAt DESC")
    List<StockMovement> findByPartIdOrderByMovedAtDesc(Long partId);

    /**
     * Net quantity of a part at a location, accounting for MOVE records that may credit or debit
     * the location as a source (location_id) or as a destination (target_location_id).
     *
     * For non-MOVE rows: quantity is a signed delta at location_id.
     * For MOVE rows: quantity is negative (debit) at location_id; the same absolute value is a
     * credit at target_location_id.  So the destination contribution is -quantity (positive).
     */
    @Query(value = """
            SELECT
              COALESCE((SELECT SUM(m1.quantity)
                        FROM stock_movement m1
                        WHERE m1.part_id = :partId AND m1.location_id = :locationId), 0)
              +
              COALESCE((SELECT SUM(-m2.quantity)
                        FROM stock_movement m2
                        WHERE m2.part_id = :partId AND m2.target_location_id = :locationId
                          AND m2.type = 'MOVE'), 0)
            """, nativeQuery = true)
    int sumQuantity(Long partId, Long locationId);

    /** True if any movement references this location as source or destination. */
    @Query("SELECT COUNT(m) > 0 FROM StockMovement m WHERE m.location.id = :locationId OR (m.targetLocation IS NOT NULL AND m.targetLocation.id = :locationId)")
    boolean existsByLocationId(Long locationId);

    /** Re-attach every movement that references {@code sourceId} (as source or destination) to {@code target}. */
    @Modifying
    @Query("UPDATE StockMovement m SET m.location = :target WHERE m.location.id = :sourceId")
    void repointLocation(Location target, Long sourceId);

    @Modifying
    @Query("UPDATE StockMovement m SET m.targetLocation = :target WHERE m.targetLocation.id = :sourceId")
    void repointTargetLocation(Location target, Long sourceId);
}
