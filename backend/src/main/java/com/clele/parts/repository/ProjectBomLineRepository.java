package com.clele.parts.repository;

import com.clele.parts.model.ProjectBomLine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ProjectBomLineRepository extends JpaRepository<ProjectBomLine, Long> {

    /**
     * Every line of one BOM in file order, with the matched part joined in — the matching screen
     * reads them all at once, so a lazy part per line would be N+1 queries over a 60-line BOM.
     */
    @Query("""
            SELECT l FROM ProjectBomLine l
            LEFT JOIN FETCH l.part
            WHERE l.bom.id = :bomId
            ORDER BY l.lineNo
            """)
    List<ProjectBomLine> findByBomIdWithPart(@Param("bomId") Long bomId);

    List<ProjectBomLine> findByBomIdOrderByLineNo(Long bomId);

    int countByBomId(Long bomId);
}
