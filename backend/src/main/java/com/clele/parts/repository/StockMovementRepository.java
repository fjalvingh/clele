package com.clele.parts.repository;

import com.clele.parts.model.StockMovement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface StockMovementRepository extends JpaRepository<StockMovement, Long> {

    @Query("SELECT m FROM StockMovement m JOIN FETCH m.location WHERE m.part.id = :partId ORDER BY m.movedAt DESC")
    List<StockMovement> findByPartIdOrderByMovedAtDesc(Long partId);
}
