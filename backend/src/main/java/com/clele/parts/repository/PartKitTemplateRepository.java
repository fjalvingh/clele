package com.clele.parts.repository;

import com.clele.parts.model.PartKitTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PartKitTemplateRepository extends JpaRepository<PartKitTemplate, Long> {

    List<PartKitTemplate> findByOrganisationIdOrderByNameAsc(Long organisationId);

    /** Another organisation's template does not exist as far as this one is concerned — hence 404. */
    Optional<PartKitTemplate> findByIdAndOrganisationId(Long id, Long organisationId);

    boolean existsByOrganisationIdAndNameIgnoreCase(Long organisationId, String name);

    boolean existsByOrganisationIdAndNameIgnoreCaseAndIdNot(Long organisationId, String name, Long id);
}
