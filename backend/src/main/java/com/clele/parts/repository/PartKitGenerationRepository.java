package com.clele.parts.repository;

import com.clele.parts.model.PartKitGeneration;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface PartKitGenerationRepository extends JpaRepository<PartKitGeneration, Long> {

    /**
     * Every run of one kit, newest first. The items are fetched with it — the history screen shows
     * each run's lines, so a lazy list per run would be a query per row.
     */
    @Query("""
            SELECT DISTINCT g FROM PartKitGeneration g
            LEFT JOIN FETCH g.items i
            LEFT JOIN FETCH i.part
            WHERE g.template.id = :templateId
            ORDER BY g.generatedAt DESC, g.id DESC
            """)
    List<PartKitGeneration> findByTemplateIdNewestFirst(Long templateId);

    /** A run of another kit is not this kit's run — hence 404 rather than 403. */
    Optional<PartKitGeneration> findByIdAndTemplateId(Long id, Long templateId);

    /**
     * The id of the kit's most recent run. Only that one may be undone: undoing an earlier run
     * would leave the later ones standing on parts and stock it had removed.
     */
    @Query("SELECT MAX(g.id) FROM PartKitGeneration g WHERE g.template.id = :templateId")
    Long findLatestId(Long templateId);
}
