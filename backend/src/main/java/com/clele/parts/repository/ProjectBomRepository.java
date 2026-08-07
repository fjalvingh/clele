package com.clele.parts.repository;

import com.clele.parts.model.ProjectBom;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ProjectBomRepository extends JpaRepository<ProjectBom, Long> {

    Optional<ProjectBom> findByProjectId(Long projectId);

    boolean existsByProjectId(Long projectId);

    /** Loads the BOM with its uploader, for the header the matching screen shows. */
    @Query("SELECT b FROM ProjectBom b LEFT JOIN FETCH b.importedBy WHERE b.project.id = :projectId")
    Optional<ProjectBom> findByProjectIdWithUploader(@Param("projectId") Long projectId);
}
