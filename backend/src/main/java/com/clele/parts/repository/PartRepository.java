package com.clele.parts.repository;

import com.clele.parts.model.Part;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PartRepository extends JpaRepository<Part, Long> {

    /**
     * A part carrying fewer than this many spec keys counts as "missing specs" — the figure the
     * dashboard tile and the Parts screen's sparse filter both report. Mirrored in
     * {@code frontend/src/api/types.ts} as {@code SPARSE_SPEC_THRESHOLD}; the two must agree or the
     * tile's count and the filtered list disagree.
     *
     * <p>Inlined as a literal in the native queries below because JPQL/native {@code @Query} text is
     * a compile-time constant expression — keep the three in step.
     */
    int SPARSE_SPEC_THRESHOLD = 5;

    Optional<Part> findByOrganisationIdAndPartNumber(Long organisationId, String partNumber);

    Optional<Part> findByIdAndOrganisationId(Long id, Long organisationId);

    /**
     * Search parts by an optional free-text term and/or category, within one organisation. The term
     * matches the part number as a case-insensitive substring, and the description, {@code details}
     * and the string values in {@code specs} via PostgreSQL full-text search (websearch syntax) —
     * so "sot-23" or "0805" finds a part by its package, which is how a part is usually
     * remembered. The three are concatenated into a single tsvector, not matched separately and
     * OR'd: a tsquery ANDs its terms, so "transistor sot-23" must find both terms in one vector.
     * The expression is indexed verbatim by V43 ({@code idx_part_search_fts}) — editing one side
     * without the other silently costs the index, not correctness.
     *
     * <p>The category filter matches the given category <em>and
     * all of its descendants at any depth</em> (resolved via a recursive walk of category.parent_id),
     * so picking a higher-level node returns parts in any of its sub-categories.
     *
     * <p>The remaining parameters are the Parts screen's advanced filters; every one of them is
     * optional and ignored when null. {@code personalNumber} matches the flag exactly;
     * {@code manufacturer} is a case-insensitive substring; {@code locationId} keeps parts that
     * hold stock in that location <em>or any location below it</em> (same recursive walk as
     * categories, so filtering on "Building A" finds stock on a shelf three levels down);
     * {@code sparseSpecs} keeps only parts carrying fewer than
     * {@link #SPARSE_SPEC_THRESHOLD} spec keys. Tag filtering is not done here — see
     * {@code PartService.search}.
     */
    @Query(value = """
            SELECT p.* FROM part p
            WHERE p.organisation_id = :orgId
              AND (:term IS NULL
                   OR p.part_number ILIKE '%' || :term || '%'
                   OR (to_tsvector('english', coalesce(p.description, ''))
                       || to_tsvector('english', coalesce(p.details, ''))
                       || jsonb_to_tsvector('english', coalesce(p.specs, '{}'), '["string"]'))
                      @@ websearch_to_tsquery('english', :term))
              AND (:categoryId IS NULL OR p.category_id IN (
                   WITH RECURSIVE subtree AS (
                       SELECT id FROM category WHERE id = :categoryId
                       UNION ALL
                       SELECT c.id FROM category c JOIN subtree s ON c.parent_id = s.id
                   )
                   SELECT id FROM subtree))
              AND (:personalNumber IS NULL OR p.personal_number = :personalNumber)
              AND (:manufacturer IS NULL OR p.manufacturer ILIKE '%' || :manufacturer || '%')
              AND (:locationId IS NULL OR EXISTS (
                   SELECT 1 FROM stock_entry se
                   WHERE se.part_id = p.id
                     AND se.quantity > 0
                     AND se.location_id IN (
                         WITH RECURSIVE loctree AS (
                             SELECT id FROM location WHERE id = :locationId
                             UNION ALL
                             SELECT l.id FROM location l JOIN loctree lt ON l.parent_id = lt.id
                         )
                         SELECT id FROM loctree)))
              AND (:sparseSpecs IS NULL OR :sparseSpecs = FALSE
                   OR (SELECT count(*) FROM jsonb_object_keys(coalesce(p.specs, '{}'))) < 5)
            ORDER BY p.part_number
            """, nativeQuery = true)
    List<Part> search(@Param("orgId") Long organisationId,
                      @Param("term") String term,
                      @Param("categoryId") Long categoryId,
                      @Param("personalNumber") Boolean personalNumber,
                      @Param("manufacturer") String manufacturer,
                      @Param("locationId") Long locationId,
                      @Param("sparseSpecs") Boolean sparseSpecs);

    /**
     * How many parts in the organisation carry fewer than {@link #SPARSE_SPEC_THRESHOLD} spec keys —
     * the "parts missing specs" figure on the dashboard.
     *
     * <p>{@code coalesce} is load-bearing: {@code jsonb_object_keys} errors on a NULL input, and a
     * part that has never been through a lookup has {@code specs IS NULL}, which is exactly the
     * population being counted.
     *
     * <p>Note the missing cast on {@code '{}'}: writing PostgreSQL's {@code '{}'::jsonb} here fails
     * at runtime, because Hibernate reads the {@code :} of {@code ::} as the start of a named
     * parameter and mangles the SQL ("syntax error at or near \":\""). No cast is needed anyway —
     * {@code p.specs} is jsonb, so coalesce coerces the literal. Use {@code cast(x as jsonb)} if an
     * explicit cast is ever unavoidable.
     */
    @Query(value = """
            SELECT count(*) FROM part p
            WHERE p.organisation_id = :orgId
              AND (SELECT count(*) FROM jsonb_object_keys(coalesce(p.specs, '{}'))) < 5
            """, nativeQuery = true)
    long countSparseSpecs(@Param("orgId") Long organisationId);

    /**
     * Fuzzy-match existing parts by part number within one organisation, for Quick Add's "do we
     * already have this?" check. Returns parts whose part_number is trigram-similar to the term
     * (typo/transposition tolerant via pg_trgm's {@code %} operator) or contains it as a
     * case-insensitive substring, best match first.
     */
    @Query(value = """
            SELECT p.* FROM part p
            WHERE p.organisation_id = :orgId
              AND (p.part_number % :term
                   OR p.part_number ILIKE '%' || :term || '%')
            ORDER BY similarity(p.part_number, :term) DESC, p.part_number
            LIMIT 10
            """, nativeQuery = true)
    List<Part> fuzzyByPartNumber(@Param("orgId") Long organisationId, @Param("term") String term);

    /**
     * Exact but case-insensitive lookups used by the BOM importer's auto-match. Both return a list
     * rather than an Optional so the caller can tell "one part" from "several": the unique
     * constraint on {@code part_number} is case-<em>sensitive</em>, and {@code mpn} carries no
     * uniqueness at all, so either can legitimately return more than one row. The importer accepts
     * a match only when exactly one distinct part comes back — a BOM line silently attached to the
     * wrong part is worse than one left for the user to decide.
     */
    List<Part> findByOrganisationIdAndPartNumberIgnoreCase(Long organisationId, String partNumber);

    List<Part> findByOrganisationIdAndMpnIgnoreCase(Long organisationId, String mpn);

    /**
     * Fuzzy-match by part number <em>or</em> MPN, returning the similarity score — the ranked
     * suggestions the BOM matching screen offers for a line that did not match exactly. The sibling
     * of {@link #fuzzyByPartNumber}, which Quick Add uses; this one also covers {@code mpn} (given
     * its own trigram index in V44), because a BOM keyed on the manufacturer part number matches
     * nothing at all otherwise.
     *
     * <p>The score is the better of the two similarities, exposed so the screen can show how
     * confident a suggestion is. Nothing auto-accepts on it.
     */
    @Query(value = """
            SELECT p.id AS id,
                   GREATEST(similarity(p.part_number, :term),
                            COALESCE(similarity(p.mpn, :term), 0)) AS score
            FROM part p
            WHERE p.organisation_id = :orgId
              AND (p.part_number % :term
                   OR p.part_number ILIKE '%' || :term || '%'
                   OR p.mpn % :term
                   OR p.mpn ILIKE '%' || :term || '%')
            ORDER BY score DESC, p.part_number
            LIMIT :limit
            """, nativeQuery = true)
    List<PartMatchView> fuzzyByPartNumberOrMpn(@Param("orgId") Long organisationId,
                                               @Param("term") String term,
                                               @Param("limit") int limit);

    /** Projection for {@link #fuzzyByPartNumberOrMpn} — a part id and how well it matched. */
    interface PartMatchView {
        Long getId();

        Double getScore();
    }

    List<Part> findByOrganisationId(Long organisationId);

    List<Part> findByOrganisationIdAndCategoryIsNull(Long organisationId);

    /** Ids of every part created by the given user in the given organisation (for bulk cleanup). */
    @Query("SELECT p.id FROM Part p WHERE p.createdBy.id = :userId AND p.organisation.id = :orgId")
    List<Long> findIdsByCreatedByIdAndOrganisationId(@Param("userId") Long userId,
                                                     @Param("orgId") Long organisationId);

    /**
     * Bulk-delete the given parts. DB-level ON DELETE CASCADE removes the dependent part_attachment
     * and stock_movement rows; stock_entry (no cascade) must be cleared first. Returns the number
     * of parts deleted.
     */
    @Modifying
    @Query("DELETE FROM Part p WHERE p.id IN :partIds")
    int deleteByIdIn(@Param("partIds") List<Long> partIds);

    boolean existsByOrganisationIdAndPartNumber(Long organisationId, String partNumber);

    boolean existsByOrganisationIdAndPartNumberAndIdNot(Long organisationId, String partNumber, Long id);

    long countByOrganisationId(Long organisationId);

    /**
     * Identity of every part across every organisation — {@code [id, partNumber, organisationName]} —
     * for the spec-value backfill CLI, which is a maintenance tool rather than a per-tenant request.
     *
     * <p>Deliberately a projection rather than {@code findAll()}: the backfill syncs each part in its
     * own transaction, so loading whole entities up front would both waste memory and hand the loop a
     * detached graph whose lazy {@code organisation} cannot be read (there is no open session outside
     * a request — the CLI profiles set {@code web-application-type: none}, so there is no OSIV
     * either). Ordered by id so a capped run samples deterministically.
     */
    @Query("SELECT p.id, p.partNumber, o.name FROM Part p JOIN p.organisation o ORDER BY p.id")
    List<Object[]> findAllForSpecBackfill();

    /**
     * Parts carrying a datasheet URL that has not been downloaded into {@code part_attachment} yet,
     * across every organisation — this drives the datasheet preflight/backfill CLI, which is a
     * maintenance tool rather than a per-tenant request. Ordered by id so a capped run
     * ({@code --datasheets.limit}) samples deterministically and a resumed run picks up where the
     * previous one stopped.
     */
    @Query("""
            SELECT p FROM Part p
            WHERE p.datasheetUrl IS NOT NULL AND TRIM(p.datasheetUrl) <> ''
              AND NOT EXISTS (SELECT 1 FROM PartAttachmentLink l
                              WHERE l.part = p
                                AND l.attachment.type = com.clele.parts.model.AttachmentType.DATASHEET)
            ORDER BY p.id
            """)
    List<Part> findWithUndownloadedDatasheet();

    /**
     * Parts whose datasheet URL is an Octopart <em>tracking</em> link — {@code
     * https://octopart.com/<id>/c1?t=<token>} — rather than a link to a file. These arrived with the
     * Partsbox import; the tokens have expired and the host now sits behind a bot wall, so they 403
     * for everyone and can only be replaced, not repaired (see {@code DatasheetResourcingService}).
     * Note this deliberately does <em>not</em> match {@code datasheet.octopart.com/*.pdf}, which
     * still serves real files.
     */
    @Query(value = """
            SELECT * FROM part
            WHERE datasheet_url ~ 'octopart\\.com/[^/]+/c1\\?'
            ORDER BY id
            """, nativeQuery = true)
    List<Part> findWithDeadOctopartDatasheetUrl();
}
