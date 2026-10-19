package com.clele.parts.repository;

import com.clele.parts.model.Supplier;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SupplierRepository extends JpaRepository<Supplier, Long> {

    List<Supplier> findByOrganisationIdOrderByNameAsc(Long organisationId);

    Optional<Supplier> findByIdAndOrganisationId(Long id, Long organisationId);

    boolean existsByOrganisationIdAndNameIgnoreCase(Long organisationId, String name);
}
