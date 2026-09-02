package com.clele.parts.repository;

import com.clele.parts.model.ProjectPart;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface ProjectPartRepository extends JpaRepository<ProjectPart, Long> {

    @Query("SELECT pp FROM ProjectPart pp JOIN FETCH pp.part WHERE pp.project.id = :projectId ORDER BY pp.id")
    List<ProjectPart> findByProjectIdWithPart(Long projectId);

    boolean existsByProjectIdAndPartId(Long projectId, Long partId);

    /** Is this part on any project's part list? Asked before a kit-generation undo deletes it. */
    boolean existsByPartId(Long partId);

    int countByProjectId(Long projectId);

    /**
     * Does any line hold less than the whole build needs? The instance count is passed in rather
     * than joined so the check is one cheap aggregate per project, not the whole list loaded.
     */
    @Query("SELECT COUNT(pp) > 0 FROM ProjectPart pp WHERE pp.project.id = :projectId "
            + "AND pp.qtyAllocated < pp.qtyPerInstance * :instanceCount")
    boolean existsShortfall(Long projectId, int instanceCount);

    void deleteByProjectId(Long projectId);
}
