package com.clele.parts.repository;

import com.clele.parts.model.Tag;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TagRepository extends JpaRepository<Tag, Long> {

    Optional<Tag> findByOrganisationIdAndNameIgnoreCase(Long organisationId, String name);

    List<Tag> findByOrganisationIdAndNameContainingIgnoreCaseOrderByNameAsc(
            Long organisationId, String name);

    List<Tag> findByOrganisationId(Long organisationId);
}
