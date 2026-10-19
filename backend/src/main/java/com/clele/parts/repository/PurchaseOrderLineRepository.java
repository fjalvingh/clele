package com.clele.parts.repository;

import com.clele.parts.model.PurchaseOrderLine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface PurchaseOrderLineRepository extends JpaRepository<PurchaseOrderLine, Long> {

    @Query("SELECT l FROM PurchaseOrderLine l JOIN FETCH l.part WHERE l.order.id = :orderId ORDER BY l.id")
    List<PurchaseOrderLine> findByOrderIdWithPart(Long orderId);

    Optional<PurchaseOrderLine> findByOrderIdAndPartId(Long orderId, Long partId);

    /** Is the part on any order? An ordered line is history, so the part must not be deleted. */
    boolean existsByPartId(Long partId);

    interface OrderTotals {
        Long getOrderId();
        long getLineCount();
        BigDecimal getTotal();
    }

    /** Line count and value per order, for the order overview — one aggregate, not every line. */
    @Query("SELECT l.order.id AS orderId, COUNT(l) AS lineCount, "
            + "SUM(COALESCE(l.unitPrice, 0) * l.quantity) AS total "
            + "FROM PurchaseOrderLine l WHERE l.order.organisation.id = :organisationId GROUP BY l.order.id")
    List<OrderTotals> totalsByOrganisation(Long organisationId);
}
