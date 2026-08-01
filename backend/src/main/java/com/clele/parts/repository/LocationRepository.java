package com.clele.parts.repository;

import com.clele.parts.dto.LocationDashboardDTO;
import com.clele.parts.model.Location;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface LocationRepository extends JpaRepository<Location, Long> {

    List<Location> findByOrganisationIdOrderByName(Long organisationId);

    Optional<Location> findByIdAndOrganisationId(Long id, Long organisationId);

    List<Location> findByOrganisationIdAndParentIsNull(Long organisationId);

    List<Location> findByParentId(Long parentId);

    boolean existsByParentId(Long parentId);

    long countByOrganisationId(Long organisationId);

    /**
     * Sibling-name uniqueness: an organisation may not have two locations with the same name under
     * the same parent (NULL parent = root level). {@code excludeId} skips the row being updated
     * (pass null on create). Null-safe parent comparison handles the root level.
     */
    @Query("""
            SELECT COUNT(l) > 0 FROM Location l
            WHERE l.organisation.id = :organisationId AND l.name = :name
              AND ((:parentId IS NULL AND l.parent IS NULL) OR l.parent.id = :parentId)
              AND (:excludeId IS NULL OR l.id <> :excludeId)
            """)
    boolean existsSibling(Long organisationId, String name, Long parentId, Long excludeId);

    /**
     * Per-root-location roll-up of the stock held in one organisation: sub-location count, distinct
     * parts, total on-hand quantity and total stock value, aggregated over each root location's
     * whole subtree. Root locations with no stock still appear.
     *
     * <p>Native because the subtree walk needs a recursive CTE, which JPQL cannot express — the same
     * reason {@code StockThresholdRepository} is native.
     */
    @Query(nativeQuery = true, value = """
            WITH RECURSIVE subtree(root_id, loc_id) AS (
              SELECT id, id FROM location WHERE parent_id IS NULL AND organisation_id = :orgId
              UNION ALL
              SELECT s.root_id, l.id FROM location l JOIN subtree s ON l.parent_id = s.loc_id
            )
            SELECT
              r.id                                    AS locationId,
              r.name                                  AS locationName,
              COUNT(DISTINCT st.loc_id)               AS locations,
              COUNT(DISTINCT se.part_id)              AS parts,
              COALESCE(SUM(se.quantity), 0)           AS totalQuantity,
              COALESCE(SUM(CASE WHEN se.unit_price IS NOT NULL
                                THEN se.quantity * se.unit_price ELSE 0 END), 0) AS totalStockValue
            FROM location r
            JOIN subtree st ON st.root_id = r.id
            LEFT JOIN stock_entry se ON se.location_id = st.loc_id
            WHERE r.parent_id IS NULL AND r.organisation_id = :orgId
            GROUP BY r.id, r.name
            ORDER BY r.name
            """)
    List<LocationDashboardView> perLocationStats(@Param("orgId") Long organisationId);

    /** Projection for {@link #perLocationStats}; getters match the column aliases. */
    interface LocationDashboardView {
        Long getLocationId();
        String getLocationName();
        Long getLocations();
        Long getParts();
        Long getTotalQuantity();
        java.math.BigDecimal getTotalStockValue();
    }

    static LocationDashboardDTO toDTO(LocationDashboardView view) {
        return LocationDashboardDTO.builder()
                .locationId(view.getLocationId())
                .locationName(view.getLocationName())
                .locations(view.getLocations())
                .parts(view.getParts())
                .totalQuantity(view.getTotalQuantity())
                .totalStockValue(view.getTotalStockValue())
                .build();
    }
}
