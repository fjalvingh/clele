package com.clele.parts.catalog;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Every statement that touches the {@code cc_*} component-cache tables. Nothing else in the app
 * reads them.
 *
 * <p><b>Plain JDBC, not JPA, on purpose.</b> The cache is a read-only snapshot loaded by an external
 * importer (see {@code CCSTRUCTURE.md}): it is not owned by Flyway, its tables are not in
 * {@code db/migration}, and mapping them as entities would put them under {@code ddl-auto: validate}
 * — so an installation without the snapshot would fail to start rather than simply doing without the
 * feature. The queries are also the kind JPQL cannot express anyway (trigram similarity, {@code
 * ts_rank}, {@code jsonb_object_keys}).
 *
 * <p>The tables live in the same database as the application schema, so this shares the ordinary
 * {@code DataSource} and needs no second connection pool.
 */
@Repository
@RequiredArgsConstructor
@Slf4j
public class ComponentCacheRepository {

    /**
     * Candidates drawn from each matching strategy before they are merged and re-ranked. Larger than
     * the returned page so a part that scores well on one strategy is not pushed out by a flood of
     * mediocre hits from another.
     */
    private static final int POOL = 60;

    private final NamedParameterJdbcTemplate jdbc;

    /** Whether the snapshot is installed and readable. Resolved once, then remembered. */
    private volatile Boolean available;

    /** One row of {@code cc_components}, with its category names resolved and its match score. */
    public record CcComponent(
            String lcsc,
            String mpn,
            String manufacturer,
            String description,
            String packageName,
            String basicExtended,
            String status,
            String category,
            String subcategory,
            Integer stock,
            Integer joints,
            BigDecimal priceQty1,
            BigDecimal priceMin,
            String datasheetUrl,
            String imageUrl,
            String productUrl,
            double score) {}

    /**
     * One attribute of one part, as the cache holds it.
     *
     * @param unitFamily  {@code cc_attribute_def.unit} — a <em>family</em> ("voltage", "count"),
     *                    not a symbol. Numeric families carry SI base units; {@code identifier} and
     *                    {@code string} carry text.
     * @param display     the vendor string rendered with units ("2.2 MHz", "-40.0 °C ~ 125.0 °C").
     *                    This is the value a human reads, and the one this app stores unless a
     *                    numeric reading is provably safe.
     * @param slotCount   how many slots the parsed value has. More than one means {@code display}
     *                    is a range or a list and the flat {@code valueNum} is only its first
     *                    element — which is positional, not semantic, so it must not be used.
     */
    public record CcAttribute(
            String name,
            String unitFamily,
            String display,
            Double valueNum,
            BigDecimal valueExact,
            String valueText,
            int slotCount) {}

    /**
     * Is the snapshot present and readable by this database user?
     *
     * <p>Probed lazily with a real {@code SELECT} rather than a catalogue lookup: {@code to_regclass}
     * answers for a table the connecting role cannot read, and the cache is loaded by a different
     * owner, so a missing {@code GRANT} is the likeliest way for this to be half-installed. Any
     * failure means "do without", never a broken screen.
     */
    public boolean available() {
        Boolean known = available;
        if (known != null) {
            return known;
        }
        boolean probed;
        try {
            jdbc.getJdbcOperations().queryForObject("SELECT 1 FROM cc_components LIMIT 1", Integer.class);
            probed = true;
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            probed = true; // installed, just empty
        } catch (Exception e) {
            log.info("Component cache not available ({}): part lookups will skip it", e.getMessage());
            probed = false;
        }
        available = probed;
        return probed;
    }

    /** Provenance rows, for the status endpoint. Empty when the cache is not installed. */
    public Map<String, String> importMeta() {
        if (!available()) {
            return Map.of();
        }
        Map<String, String> meta = new LinkedHashMap<>();
        jdbc.getJdbcOperations().query("SELECT key, value FROM cc_import_meta",
                rs -> { meta.put(rs.getString("key"), rs.getString("value")); });
        return meta;
    }

    /**
     * Rank the cache against a search term.
     *
     * <p>Three strategies, each capped and then merged on the best score per part, because they find
     * different things and no one of them is enough on its own:
     * <ul>
     *   <li><b>trigram over {@code mfr}</b> — the workhorse. Typo- and suffix-tolerant, so
     *       {@code IRF540N} still finds {@code IRF540NPBF}. An exact (case-insensitive) MPN is
     *       promoted to a flat 1.0 so it cannot be outranked by a longer part number that happens to
     *       share more trigrams.</li>
     *   <li><b>the LCSC code</b> — {@code C8734} is a legitimate thing to type and matches nothing
     *       by similarity.</li>
     *   <li><b>full text over part number + description</b> — for "0402 X7R 100nF", where no single
     *       part number resembles the query at all. Scored below the other two: a description hit is
     *       weaker evidence than a part-number hit.</li>
     * </ul>
     *
     * <p>The case-insensitive comparison sits <em>inside</em> the trigram branch, applied to the
     * candidates it already found. Written as a top-level {@code WHERE lower(mfr) = lower(:term)} it
     * cannot use any index and seq-scans a 2 GB table on every keystroke.
     *
     * <p>Ties break on stock: several distributors list the same MPN, and the one actually held is
     * the more useful row to show first.
     */
    public List<CcComponent> search(String term, int limit) {
        if (!available()) {
            return List.of();
        }
        String sql = """
                WITH fuzzy AS (
                    SELECT c.lcsc,
                           CASE WHEN lower(c.mfr) = lower(:term) THEN 1.0
                                ELSE 0.9 * similarity(c.mfr, :term) END::float8 AS s
                    FROM cc_components c
                    WHERE c.mfr % :term
                    ORDER BY similarity(c.mfr, :term) DESC
                    LIMIT :pool
                ),
                code AS (
                    SELECT c.lcsc, 1.0::float8 AS s
                    FROM cc_components c
                    WHERE c.lcsc = upper(:term)
                ),
                fts AS (
                    SELECT c.lcsc,
                           (0.5 * (0.2 + ts_rank(c.search, websearch_to_tsquery('simple', :term))))::float8 AS s
                    FROM cc_components c
                    WHERE c.search @@ websearch_to_tsquery('simple', :term)
                    ORDER BY ts_rank(c.search, websearch_to_tsquery('simple', :term)) DESC,
                             c.stock DESC NULLS LAST
                    LIMIT :pool
                ),
                best AS (
                    SELECT lcsc, max(s) AS s
                    FROM (SELECT * FROM fuzzy UNION ALL SELECT * FROM code UNION ALL SELECT * FROM fts) u
                    GROUP BY lcsc
                )
                SELECT c.lcsc, c.mfr, c.manufacturer, c.description, c.package, c.basic_extended,
                       c.status, cat.category, cat.subcategory, c.stock, c.joints,
                       c.price_qty1, c.price_min, c.datasheet, c.image_url, c.product_url, b.s AS score
                FROM best b
                JOIN cc_components c ON c.lcsc = b.lcsc
                LEFT JOIN cc_categories cat ON cat.id = c.category_id
                ORDER BY b.s DESC, (c.stock > 0) DESC, c.stock DESC NULLS LAST, c.lcsc
                LIMIT :limit
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("term", term)
                .addValue("pool", POOL)
                .addValue("limit", limit);
        return jdbc.query(sql, params, (rs, i) -> mapComponent(rs));
    }

    /** One part by its LCSC code, with no score (it was not reached by searching). */
    public Optional<CcComponent> findByLcsc(String lcsc) {
        if (!available()) {
            return Optional.empty();
        }
        String sql = """
                SELECT c.lcsc, c.mfr, c.manufacturer, c.description, c.package, c.basic_extended,
                       c.status, cat.category, cat.subcategory, c.stock, c.joints,
                       c.price_qty1, c.price_min, c.datasheet, c.image_url, c.product_url,
                       1.0::float8 AS score
                FROM cc_components c
                LEFT JOIN cc_categories cat ON cat.id = c.category_id
                WHERE c.lcsc = :lcsc
                """;
        return jdbc.query(sql, new MapSqlParameterSource("lcsc", lcsc), (rs, i) -> mapComponent(rs))
                .stream().findFirst();
    }

    /**
     * Every attribute of one part.
     *
     * <p>{@code slot_count} is computed here rather than inferred later because it is the only thing
     * that distinguishes "16 MHz" (one slot, {@code value_num} means what it says) from
     * "1 V ~ 18 V, -18 V ~ -1 V" (four slots, {@code value_num} is whichever the vendor listed
     * first).
     */
    public List<CcAttribute> attributes(String lcsc) {
        if (!available()) {
            return List.of();
        }
        String sql = """
                SELECT d.name AS name, d.unit AS unit_family, v.display, v.value_num, v.value_exact,
                       v.value_text, (SELECT count(*) FROM jsonb_object_keys(v.slots)) AS slot_count
                FROM cc_component_attributes ca
                JOIN cc_attribute_def d ON d.id = ca.def_id
                JOIN cc_attribute_value v ON v.id = ca.value_id
                WHERE ca.lcsc = :lcsc
                ORDER BY d.name
                """;
        return jdbc.query(sql, new MapSqlParameterSource("lcsc", lcsc), (rs, i) -> new CcAttribute(
                rs.getString("name"),
                rs.getString("unit_family"),
                rs.getString("display"),
                (Double) rs.getObject("value_num"),
                rs.getBigDecimal("value_exact"),
                rs.getString("value_text"),
                rs.getInt("slot_count")));
    }

    /**
     * How many attributes each of the given parts carries — the "N specifications" figure on a
     * search result. One grouped query for the whole page rather than one per row.
     */
    public Map<String, Integer> attributeCounts(List<String> lcscs) {
        if (!available() || lcscs.isEmpty()) {
            return Map.of();
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        jdbc.query("SELECT lcsc, count(*) AS n FROM cc_component_attributes WHERE lcsc IN (:lcscs) GROUP BY lcsc",
                new MapSqlParameterSource("lcscs", lcscs),
                rs -> { counts.put(rs.getString("lcsc"), rs.getInt("n")); });
        return counts;
    }

    private static CcComponent mapComponent(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new CcComponent(
                rs.getString("lcsc"),
                rs.getString("mfr"),
                rs.getString("manufacturer"),
                rs.getString("description"),
                rs.getString("package"),
                rs.getString("basic_extended"),
                rs.getString("status"),
                rs.getString("category"),
                rs.getString("subcategory"),
                (Integer) rs.getObject("stock"),
                (Integer) rs.getObject("joints"),
                rs.getBigDecimal("price_qty1"),
                rs.getBigDecimal("price_min"),
                rs.getString("datasheet"),
                rs.getString("image_url"),
                rs.getString("product_url"),
                rs.getDouble("score"));
    }
}
