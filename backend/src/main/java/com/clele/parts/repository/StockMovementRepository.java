package com.clele.parts.repository;

import com.clele.parts.model.Location;
import com.clele.parts.model.StockMovement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface StockMovementRepository extends JpaRepository<StockMovement, Long> {

    @Query("SELECT m FROM StockMovement m JOIN FETCH m.location WHERE m.part.id = :partId ORDER BY m.movedAt DESC")
    List<StockMovement> findByPartIdOrderByMovedAtDesc(Long partId);

    @Query("SELECT COALESCE(SUM(m.quantity), 0) FROM StockMovement m WHERE m.part.id = :partId AND m.location.id = :locationId")
    int sumQuantity(Long partId, Long locationId);

    boolean existsByLocationId(Long locationId);

    /** Re-attach every movement at {@code sourceId} to {@code target} (used by location merge to
     *  preserve the ledger when the source location is removed). */
    @Modifying
    @Query("UPDATE StockMovement m SET m.location = :target WHERE m.location.id = :sourceId")
    void repointLocation(Location target, Long sourceId);
}
