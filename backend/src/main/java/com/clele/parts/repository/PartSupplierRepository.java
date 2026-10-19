package com.clele.parts.repository;

import com.clele.parts.model.PartSupplier;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface PartSupplierRepository extends JpaRepository<PartSupplier, Long> {

    Optional<PartSupplier> findByPartIdAndSupplierId(Long partId, Long supplierId);

    @Query("SELECT ps FROM PartSupplier ps JOIN FETCH ps.supplier WHERE ps.part.id = :partId "
            + "ORDER BY ps.supplier.name")
    List<PartSupplier> findByPartIdWithSupplier(Long partId);

    /** The most recently learned price at any supplier, as the default for a new order list item. */
    Optional<PartSupplier> findFirstByPartIdAndLastUnitPriceIsNotNullOrderByUpdatedAtDesc(Long partId);

    boolean existsBySupplierId(Long supplierId);
}
