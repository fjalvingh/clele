package com.clele.parts.repository;

import com.clele.parts.model.StockEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface StockEntryRepository extends JpaRepository<StockEntry, Long> {

    @Query("SELECT s FROM StockEntry s JOIN FETCH s.part JOIN FETCH s.location WHERE s.part.id = :partId")
    List<StockEntry> findByPartId(Long partId);

    @Query("SELECT s FROM StockEntry s JOIN FETCH s.part JOIN FETCH s.location WHERE s.part.id = :partId AND s.location.id = :locationId")
    Optional<StockEntry> findByPartIdAndLocationId(Long partId, Long locationId);

    @Query("SELECT s FROM StockEntry s JOIN FETCH s.part JOIN FETCH s.location WHERE s.location.id = :locationId")
    List<StockEntry> findByLocationId(Long locationId);

    /** Every on-hand row of one organisation, found through the location it sits in. */
    @Query("""
            SELECT s FROM StockEntry s JOIN FETCH s.part JOIN FETCH s.location
            WHERE s.location.organisation.id = :organisationId
            """)
    List<StockEntry> findByOrganisationId(Long organisationId);

    void deleteByLocationId(Long locationId);

    @Query("""
            SELECT COALESCE(SUM(s.quantity * s.unitPrice), 0) FROM StockEntry s
            WHERE s.unitPrice IS NOT NULL AND s.location.organisation.id = :organisationId
            """)
    java.math.BigDecimal totalStockValue(Long organisationId);

    void deleteByPartId(Long partId);

    @Modifying
    @Query("DELETE FROM StockEntry s WHERE s.part.id IN :partIds")
    void deleteByPartIdIn(List<Long> partIds);

    boolean existsByPartIdAndLocationId(Long partId, Long locationId);

    boolean existsByLocationId(Long locationId);

    boolean existsByPartIdAndLocationIdAndIdNot(Long partId, Long locationId, Long id);

    /**
     * On-hand totals per part across the whole organisation. Locations are shared by every member,
     * so the "In Stock" column is an organisation figure rather than a per-user one.
     */
    @Query("""
            SELECT s.part.id, SUM(s.quantity) FROM StockEntry s
            WHERE s.part.id IN :partIds AND s.location.organisation.id = :organisationId
            GROUP BY s.part.id
            """)
    List<Object[]> sumQuantityByPartIdsAndOrganisationId(List<Long> partIds, Long organisationId);
}
