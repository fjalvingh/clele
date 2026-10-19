package com.clele.parts.repository;

import com.clele.parts.model.OrderItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {

    /** The order list proper: items not yet put on an order. */
    @Query("SELECT i FROM OrderItem i JOIN FETCH i.part LEFT JOIN FETCH i.project "
            + "WHERE i.organisation.id = :organisationId AND i.line IS NULL ORDER BY i.createdAt")
    List<OrderItem> findOpenByOrganisationId(Long organisationId);

    @Query("SELECT i FROM OrderItem i JOIN FETCH i.part LEFT JOIN FETCH i.project "
            + "WHERE i.line.order.id = :orderId ORDER BY i.id")
    List<OrderItem> findByOrderId(Long orderId);

    @Query("SELECT i FROM OrderItem i LEFT JOIN FETCH i.project WHERE i.line.id IN :lineIds ORDER BY i.id")
    List<OrderItem> findByLineIdIn(Collection<Long> lineIds);

    List<OrderItem> findByLineId(Long lineId);

    Optional<OrderItem> findByIdAndOrganisationId(Long id, Long organisationId);

    List<OrderItem> findByIdInAndOrganisationId(Collection<Long> ids, Long organisationId);
}
