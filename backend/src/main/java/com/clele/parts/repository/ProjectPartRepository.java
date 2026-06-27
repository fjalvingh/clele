package com.clele.parts.repository;

import com.clele.parts.model.ProjectPart;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface ProjectPartRepository extends JpaRepository<ProjectPart, Long> {

    @Query("SELECT pp FROM ProjectPart pp JOIN FETCH pp.part WHERE pp.project.id = :projectId ORDER BY pp.id")
    List<ProjectPart> findByProjectIdWithPart(Long projectId);

    boolean existsByProjectIdAndPartId(Long projectId, Long partId);

    int countByProjectId(Long projectId);

    void deleteByProjectId(Long projectId);
}
