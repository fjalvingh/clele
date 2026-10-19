package com.clele.parts.repository;

import com.clele.parts.model.PurchaseOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrder, Long> {

    @Query("SELECT o FROM PurchaseOrder o JOIN FETCH o.supplier WHERE o.organisation.id = :organisationId "
            + "ORDER BY o.createdAt DESC")
    List<PurchaseOrder> findByOrganisationIdWithSupplier(Long organisationId);

    Optional<PurchaseOrder> findByIdAndOrganisationId(Long id, Long organisationId);

    boolean existsBySupplierId(Long supplierId);
}
