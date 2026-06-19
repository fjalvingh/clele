package com.clele.parts.repository;

import com.clele.parts.model.StockEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface StockEntryRepository extends JpaRepository<StockEntry, Long> {

    @Query("SELECT s FROM StockEntry s JOIN FETCH s.part JOIN FETCH s.location WHERE s.part.id = :partId")
    List<StockEntry> findByPartId(Long partId);

    @Query("SELECT s FROM StockEntry s JOIN FETCH s.part JOIN FETCH s.location WHERE s.quantity < s.minimumQuantity")
    List<StockEntry> findLowStock();

    @Query("SELECT COUNT(DISTINCT s) FROM StockEntry s WHERE s.quantity < s.minimumQuantity")
    long countLowStock();

    @Query("SELECT COALESCE(SUM(s.quantity * s.unitPrice), 0) FROM StockEntry s WHERE s.unitPrice IS NOT NULL")
    java.math.BigDecimal totalStockValue();

    void deleteByPartId(Long partId);

    boolean existsByPartIdAndLocationId(Long partId, Long locationId);

    boolean existsByLocationId(Long locationId);

    boolean existsByPartIdAndLocationIdAndIdNot(Long partId, Long locationId, Long id);
}
