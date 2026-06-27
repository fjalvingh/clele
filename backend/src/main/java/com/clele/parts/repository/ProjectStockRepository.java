package com.clele.parts.repository;

import com.clele.parts.model.ProjectStock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface ProjectStockRepository extends JpaRepository<ProjectStock, Long> {

    @Query("SELECT ps FROM ProjectStock ps JOIN FETCH ps.part JOIN FETCH ps.location LEFT JOIN FETCH ps.addedByUser WHERE ps.project.id = :projectId ORDER BY ps.addedAt DESC")
    List<ProjectStock> findByProjectIdWithDetails(Long projectId);

    @Query("SELECT COALESCE(SUM(ps.quantity), 0) FROM ProjectStock ps WHERE ps.project.id = :projectId AND ps.part.id = :partId")
    int sumQuantityByProjectIdAndPartId(Long projectId, Long partId);
}
