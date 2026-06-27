package com.clele.parts.repository;

import com.clele.parts.model.StockThreshold;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface StockThresholdRepository extends JpaRepository<StockThreshold, Long> {

    List<StockThreshold> findByPartId(Long partId);

    Optional<StockThreshold> findByPartIdAndLocationId(Long partId, Long locationId);

    /** All thresholds with their current subtree totals. */
    @Query(nativeQuery = true, value = """
            WITH RECURSIVE subtree(root_id, loc_id) AS (
              SELECT id, id FROM location WHERE parent_id IS NULL
              UNION ALL
              SELECT s.root_id, l.id FROM location l JOIN subtree s ON l.parent_id = s.loc_id
            )
            SELECT
              pst.id            AS id,
              pst.part_id       AS partId,
              pst.location_id   AS locationId,
              pst.minimum_quantity AS minimumQuantity,
              COALESCE(SUM(se.quantity), 0) AS totalQuantity,
              p.part_number     AS partNumber,
              p.part_number     AS partName,
              l.name            AS locationName
            FROM part_stock_threshold pst
            JOIN part p ON p.id = pst.part_id
            JOIN location l ON l.id = pst.location_id
            LEFT JOIN subtree st ON st.root_id = pst.location_id
            LEFT JOIN stock_entry se ON se.part_id = pst.part_id AND se.location_id = st.loc_id
            GROUP BY pst.id, pst.part_id, pst.location_id, pst.minimum_quantity,
                     p.part_number, l.name
            ORDER BY p.part_number, l.name
            """)
    List<StockThresholdView> findAllWithTotals();

    /** All thresholds for a specific part, with current subtree totals. */
    @Query(nativeQuery = true, value = """
            WITH RECURSIVE subtree(root_id, loc_id) AS (
              SELECT id, id FROM location WHERE parent_id IS NULL
              UNION ALL
              SELECT s.root_id, l.id FROM location l JOIN subtree s ON l.parent_id = s.loc_id
            )
            SELECT
              pst.id            AS id,
              pst.part_id       AS partId,
              pst.location_id   AS locationId,
              pst.minimum_quantity AS minimumQuantity,
              COALESCE(SUM(se.quantity), 0) AS totalQuantity,
              p.part_number     AS partNumber,
              p.part_number     AS partName,
              l.name            AS locationName
            FROM part_stock_threshold pst
            JOIN part p ON p.id = pst.part_id
            JOIN location l ON l.id = pst.location_id
            LEFT JOIN subtree st ON st.root_id = pst.location_id
            LEFT JOIN stock_entry se ON se.part_id = pst.part_id AND se.location_id = st.loc_id
            WHERE pst.part_id = :partId
            GROUP BY pst.id, pst.part_id, pst.location_id, pst.minimum_quantity,
                     p.part_number, l.name
            ORDER BY l.name
            """)
    List<StockThresholdView> findByPartIdWithTotals(Long partId);

    /** Thresholds where total on-hand across the root's subtree is below minimum. */
    @Query(nativeQuery = true, value = """
            WITH RECURSIVE subtree(root_id, loc_id) AS (
              SELECT id, id FROM location WHERE parent_id IS NULL
              UNION ALL
              SELECT s.root_id, l.id FROM location l JOIN subtree s ON l.parent_id = s.loc_id
            )
            SELECT
              pst.id            AS id,
              pst.part_id       AS partId,
              pst.location_id   AS locationId,
              pst.minimum_quantity AS minimumQuantity,
              COALESCE(SUM(se.quantity), 0) AS totalQuantity,
              p.part_number     AS partNumber,
              p.part_number     AS partName,
              l.name            AS locationName
            FROM part_stock_threshold pst
            JOIN part p ON p.id = pst.part_id
            JOIN location l ON l.id = pst.location_id
            LEFT JOIN subtree st ON st.root_id = pst.location_id
            LEFT JOIN stock_entry se ON se.part_id = pst.part_id AND se.location_id = st.loc_id
            GROUP BY pst.id, pst.part_id, pst.location_id, pst.minimum_quantity,
                     p.part_number, l.name
            HAVING COALESCE(SUM(se.quantity), 0) < pst.minimum_quantity
            ORDER BY p.part_number, l.name
            """)
    List<StockThresholdView> findLowStock();

    @Query(nativeQuery = true, value = """
            WITH RECURSIVE subtree(root_id, loc_id) AS (
              SELECT id, id FROM location WHERE parent_id IS NULL
              UNION ALL
              SELECT s.root_id, l.id FROM location l JOIN subtree s ON l.parent_id = s.loc_id
            )
            SELECT COUNT(*) FROM (
              SELECT pst.id
              FROM part_stock_threshold pst
              LEFT JOIN subtree st ON st.root_id = pst.location_id
              LEFT JOIN stock_entry se ON se.part_id = pst.part_id AND se.location_id = st.loc_id
              GROUP BY pst.id, pst.minimum_quantity
              HAVING COALESCE(SUM(se.quantity), 0) < pst.minimum_quantity
            ) sub
            """)
    long countLowStock();
}
