package com.clele.parts.repository;

import com.clele.parts.model.ProjectStock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface ProjectStockRepository extends JpaRepository<ProjectStock, Long> {

    @Query("SELECT ps FROM ProjectStock ps JOIN FETCH ps.part JOIN FETCH ps.location LEFT JOIN FETCH ps.addedByUser WHERE ps.project.id = :projectId ORDER BY ps.addedAt DESC")
    List<ProjectStock> findByProjectIdWithDetails(Long projectId);

    /**
     * One part's allocation batches, newest first. Returning stock walks them in this order so the
     * most recently taken parts go back first — the batch whose source location is most likely to
     * still be the right one.
     */
    @Query("SELECT ps FROM ProjectStock ps JOIN FETCH ps.location WHERE ps.project.id = :projectId AND ps.part.id = :partId ORDER BY ps.addedAt DESC, ps.id DESC")
    List<ProjectStock> findByProjectIdAndPartIdNewestFirst(Long projectId, Long partId);

    @Query("SELECT COALESCE(SUM(ps.quantity), 0) FROM ProjectStock ps WHERE ps.project.id = :projectId AND ps.part.id = :partId")
    int sumQuantityByProjectIdAndPartId(Long projectId, Long partId);

    /** Has this part been pulled into any project? Asked before a kit-generation undo deletes it. */
    boolean existsByPartId(Long partId);

    void deleteByProjectId(Long projectId);
}
