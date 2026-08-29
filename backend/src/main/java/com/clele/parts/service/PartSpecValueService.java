package com.clele.parts.service;

import com.clele.parts.model.*;
import com.clele.parts.repository.PartSpecValueRepository;
import com.clele.parts.repository.SpecDefinitionRepository;
import com.clele.parts.repository.SpecGroupRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;

/**
 * Writes a part's specs into the typed {@code part_spec_value} rows.
 *
 * <h2>Sync, not merge</h2>
 *
 * The single entry point {@link #sync(Part)} takes the part's <em>already-resolved</em> spec map and
 * makes the rows match it exactly. It deliberately does not reimplement
 * {@code PartRequest.specsMode} MERGE/REPLACE: by the time a part is saved, {@code resolveSpecs} has
 * already applied that, so the map is authoritative and copying its outcome is both simpler and
 * self-correcting. Sync is idempotent, which is what lets the same method serve the intake paths and
 * the bulk backfill.
 *
 * <p>While the JSONB remains the read source (migration step 1), a bug here cannot lose data — the
 * rows are derived and can be rebuilt by syncing every part again.
 *
 * <h2>How a value is classified</h2>
 *
 * <p><b>The definition's {@code data_type} decides which columns are used — nothing else.</b>
 *
 * <ul>
 *   <li><b>NUMBER</b> — the value is read by {@link NumericSpecParser} into
 *       {@code value_num}/{@code value_min}/{@code value_max}, in the field's base SI unit. There is
 *       no scalar-versus-range distinction: empty bounds <em>are</em> a scalar.</li>
 *   <li><b>TEXT, SELECT, BOOLEAN</b> — the value is stored verbatim in {@code value_text}. It is
 *       never parsed, never split into bounds, and never becomes a number however numeric it
 *       looks.</li>
 * </ul>
 *
 * <p><b>A NUMBER value that will not parse is dropped, not parked as text</b> (and reported in
 * {@link SyncResult#unparsed()}). This is the point of the rule: a number sitting in
 * {@code value_text} is invisible to every parametric query — "supply voltage between 3 and 5 V"
 * cannot see a part whose supply voltage reads {@code "5V ± 10%"} — so the field looks populated
 * while answering nothing. An empty field is the honest version of that, and it is visible. The
 * defence against losing something real is the parser's tolerance, not a text fallback.
 *
 * <p>⚠️ The corollary: <b>a mis-typed definition destroys values.</b> A field that is really text
 * ({@code "2K x 8"}, {@code "0805"}) but is declared NUMBER will lose its values the next time each
 * part is saved. {@code SpecResyncService} exists to show that list before it happens.
 *
 * <p>A definition auto-created for an unknown key still infers its type from the one value in hand
 * ({@link #inferDataType}), which is where the "0805 is a code, not the number 805" rule now earns
 * its keep: it decides the <em>type</em>, and the type decides everything after.
 *
 * <p>BOOLEAN definitions are text ({@code "true"}/{@code "false"}) — filtered by equality, never by
 * range, so they need no numeric column.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PartSpecValueService {

    private final PartSpecValueRepository valueRepo;
    private final SpecDefinitionRepository specRepo;
    private final SpecGroupRepository groupRepo;
    private final com.clele.parts.repository.PartRepository partRepo;

    private static final String DEFAULT_GROUP_NAME = "Technical";

    /** What one sync did, for the backfill report. */
    /**
     * @param refused  NUMBER values that could not be read as numbers, and so were dropped. Each one
     *                 is listed in {@code unparsed} as {@code key=value}.
     */
    public record SyncResult(int scalars, int ranges, int texts, int refused, int definitionsCreated,
                             List<String> unparsed) {
        public int total() {
            return scalars + ranges + texts;
        }

        /** A part that vanished between listing and syncing — an empty outcome, not an error. */
        static SyncResult empty() {
            return new SyncResult(0, 0, 0, 0, 0, List.of());
        }
    }

    /**
     * Make {@code part}'s typed rows match {@code incoming} — <b>the single write path for a spec
     * value</b>. The part must already be persisted; the rows are keyed on its id.
     *
     * <p>The map is passed in rather than read off the part: {@code part.specs} is gone (V53), so
     * the rows are the storage and the map is only ever a transient input on its way here. Callers
     * that need the part's current values to merge against read them back with {@link #specsOf}.
     */
    @Transactional
    public SyncResult sync(Part part, Map<String, Object> incoming) {
        return apply(part, incoming, true);
    }

    /**
     * What {@link #sync} would do to this part, writing nothing.
     *
     * <p>The same code path, deliberately: a preview assembled by a second implementation of the
     * rules would drift from them, and the whole reason it exists is to be believed — it is what
     * says which values a re-sync is about to drop.
     */
    @Transactional(readOnly = true)
    public SyncResult preview(Part part, Map<String, Object> incoming) {
        return apply(part, incoming, false);
    }

    private SyncResult apply(Part part, Map<String, Object> incoming, boolean commit) {
        Map<String, Object> specs = incoming == null ? Map.of() : incoming;
        Long orgId = part.getOrganisation().getId();

        Map<Long, PartSpecValue> existing = new HashMap<>();
        for (PartSpecValue v : valueRepo.findByPartId(part.getId())) {
            existing.put(v.getSpecDefinition().getId(), v);
        }

        int scalars = 0, ranges = 0, texts = 0, refused = 0, created = 0;
        List<String> unparsed = new ArrayList<>();
        // Definitions this map has already decided (whatever the outcome), so two aliases of one
        // spec do not both write it...
        Set<Long> decided = new HashSet<>();
        // ...and those that end up keeping a row. A refused value is deliberately absent from this
        // one, which is what makes the stale sweep below delete the row it used to have.
        Set<Long> seen = new HashSet<>();
        List<PartSpecValue> toSave = new ArrayList<>();

        for (Map.Entry<String, Object> entry : specs.entrySet()) {
            String key = entry.getKey();
            Object raw = entry.getValue();
            if (key == null || key.isBlank() || raw == null || String.valueOf(raw).isBlank()) continue;

            SpecDefinition def = specRepo.findByOrganisationIdAndJsonName(orgId, key).orElse(null);
            if (def == null) {
                if (!commit) continue;   // a preview must not create definitions
                def = createDefinition(part.getOrganisation(), key, raw);
                created++;
            }
            if (!decided.add(def.getId())) continue;   // two aliases of one spec on the same part

            Classification c = classify(raw, def);
            if (c.shape() == Shape.REFUSED) {
                // Dropped, not stored as text — see the class docs. The row it may have had goes
                // with it, because the definition never reaches `seen`.
                refused++;
                unparsed.add(key + "=" + raw);
                continue;
            }

            PartSpecValue row = existing.get(def.getId());
            if (row == null) row = PartSpecValue.text(part, def, null);
            switch (c.shape()) {
                case NUMERIC -> {
                    row.setNumeric(c.num(), c.min(), c.max());
                    // Counted for the report only: a value with bounds is reported as a range
                    // whether or not it also carries a nominal.
                    if (c.hasBounds()) ranges++; else scalars++;
                }
                case TEXT -> {
                    row.setText(textOf(raw));
                    texts++;
                }
                case REFUSED -> throw new IllegalStateException("handled above");
            }
            seen.add(def.getId());
            toSave.add(row);
        }

        // Keys the part no longer carries lose their row: the map is authoritative.
        List<PartSpecValue> stale = existing.entrySet().stream()
                .filter(e -> !seen.contains(e.getKey()))
                .map(Map.Entry::getValue)
                .toList();

        if (commit) {
            if (!stale.isEmpty()) valueRepo.deleteAll(stale);
            if (!toSave.isEmpty()) valueRepo.saveAll(toSave);

            // The search projection the Parts free-text index covers. Written here because this is
            // the only path that writes a spec value, so it cannot fall behind the rows it
            // summarises.
            part.setSpecText(specTextOf(toSave));
        }

        return new SyncResult(scalars, ranges, texts, refused, created, unparsed);
    }

    /**
     * The textual spec values run together, for {@code part.spec_text}. Only text rows contribute:
     * a parsed number is stored in its base SI unit (a 7.62 mm width is {@code 0.00762}, a 33 ns
     * delay {@code 0.000000033}), which tokenises into strings nobody will ever type and would only
     * bloat the index — the same rule V43 applied when it indexed only the JSONB's string values.
     */
    private static String specTextOf(List<PartSpecValue> rows) {
        String joined = rows.stream()
                .map(PartSpecValue::getValueText)
                .filter(v -> v != null && !v.isBlank())
                .collect(java.util.stream.Collectors.joining(" "));
        return joined.isEmpty() ? null : joined;
    }

    /**
     * Re-read one part's stored values under the current rules — the unit of work the re-sync CLI
     * repeats, in its own transaction so a long run neither holds one open nor accumulates a
     * persistence context the size of the catalogue.
     *
     * <p>The part is re-read here rather than passed in for the same reason: the caller iterates ids.
     */
    @Transactional
    public SyncResult resync(Long partId, boolean commit) {
        Part part = partRepo.findById(partId).orElse(null);
        if (part == null) return SyncResult.empty();
        Map<String, Object> specs = specsOf(partId);
        if (specs.isEmpty()) return SyncResult.empty();
        if (!commit) return apply(part, specs, false);
        SyncResult result = apply(part, specs, true);
        partRepo.save(part);   // apply() rewrote spec_text on it
        return result;
    }

    /**
     * A part's specs as the {@code jsonName -> value} map every caller expects — the read side of
     * the typed rows, and since V53 the only place a part's specs come from.
     *
     * <p><b>The map keeps exactly the shape the old JSONB had</b>, which is what let the storage be
     * swapped underneath without a client noticing: a numeric row yields the bare base-unit number
     * (not a rendering), a range yields the {@code "min..max"} form, and text passes through.
     * Rendering stays where it already lives — {@code units.ts} on the way to the screen — because
     * the edit widgets bind to the stored base number and would break on {@code "4k7"}.
     *
     * <p>It is also what a caller merges against: an update that changes one spec needs the part's
     * current values, and this is where they now live.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> specsOf(Long partId) {
        return toMap(valueRepo.findByPartId(partId));
    }

    /**
     * The same for many parts at once, so a search result costs one query rather than one per part.
     * Parts with no spec values are simply absent from the result.
     */
    @Transactional(readOnly = true)
    public Map<Long, Map<String, Object>> specsOf(Collection<Long> partIds) {
        if (partIds.isEmpty()) return Map.of();
        Map<Long, List<PartSpecValue>> byPart = new LinkedHashMap<>();
        for (PartSpecValue v : valueRepo.findByPartIdIn(partIds)) {
            byPart.computeIfAbsent(v.getPart().getId(), k -> new ArrayList<>()).add(v);
        }
        Map<Long, Map<String, Object>> result = new LinkedHashMap<>();
        byPart.forEach((partId, rows) -> result.put(partId, toMap(rows)));
        return result;
    }

    private static Map<String, Object> toMap(List<PartSpecValue> rows) {
        Map<String, Object> specs = new LinkedHashMap<>();
        rows.stream()
                .sorted(Comparator.comparing(v -> v.getSpecDefinition().getJsonName()))
                .forEach(v -> specs.put(v.getSpecDefinition().getJsonName(), valueOf(v)));
        return specs;
    }

    /**
     * One row as the JSONB would have held it — a bare number, {@code "min..max"}, or, for a value
     * that carries both (V56), {@code "min..nominal..max"}.
     */
    private static Object valueOf(PartSpecValue v) {
        if (v.isRange()) {
            String bounds = bound(v.getValueMin()) + ".." + bound(v.getValueMax());
            if (v.getValueNum() == null) return bounds;
            return bound(v.getValueMin()) + ".." + bound(v.getValueNum()) + ".." + bound(v.getValueMax());
        }
        if (v.getValueNum() != null) return v.getValueNum().stripTrailingZeros();
        return v.getValueText();
    }

    /**
     * A value on its way into {@code value_text}.
     *
     * <p>⚠️ <b>{@code String.valueOf(BigDecimal)} is not good enough.</b> A value can arrive here as
     * a number — {@link #specsOf} hands back {@code BigDecimal} for a numeric row, and a re-sync
     * feeds that straight back in — and {@code BigDecimal.toString()} switches to scientific
     * notation once trailing zeros are stripped. That turned the Schedule B code 8536695050 into the
     * string {@code "8.53669505E+9"}: the same number, but not a code anyone can read or search for.
     */
    private static String textOf(Object raw) {
        if (raw instanceof BigDecimal d) return d.stripTrailingZeros().toPlainString();
        if (raw instanceof Number n) {
            return new BigDecimal(n.toString()).stripTrailingZeros().toPlainString();
        }
        return MetricUnitParser.normalizeSpaces(String.valueOf(raw));
    }

    /** An open bound is written "null", which is the form Partsbox sent and the parser reads back. */
    private static String bound(BigDecimal b) {
        return b == null ? "null" : b.stripTrailingZeros().toPlainString();
    }

    /** Drop a part's rows outright — for the paths that delete a part's specs wholesale. */
    @Transactional
    public void deleteForPart(Long partId) {
        valueRepo.deleteByPartId(partId);
    }

    /** What the definition's data type says this value is: a number, raw text, or not storable. */
    private enum Shape { NUMERIC, TEXT, REFUSED }

    private record Classification(Shape shape, BigDecimal num, BigDecimal min, BigDecimal max) {
        static Classification numeric(BigDecimal v, BigDecimal lo, BigDecimal hi) {
            return new Classification(Shape.NUMERIC, v, lo, hi);
        }
        static Classification text() { return new Classification(Shape.TEXT, null, null, null); }
        static Classification refused() { return new Classification(Shape.REFUSED, null, null, null); }

        boolean hasBounds() { return min != null || max != null; }
    }

    /**
     * The value's shape, decided by the <b>definition's data type</b> and nothing else.
     *
     * <p>A NUMBER field is read by {@link NumericSpecParser}; anything it cannot read is refused and
     * the value is dropped. Every other type is stored verbatim, however numeric it looks — that is
     * what keeps {@code "0805"} an imperial case code and {@code "2K x 8"} a memory organisation
     * rather than silently becoming 805 and 2000.
     */
    private Classification classify(Object raw, SpecDefinition def) {
        if (!"NUMBER".equals(def.getDataType())) return Classification.text();

        // A JSON number needs no parsing: it is already a number in whatever unit the field means.
        if (raw instanceof Number n) {
            return Classification.numeric(storedScale(new BigDecimal(n.toString())), null, null);
        }
        return NumericSpecParser.parse(String.valueOf(raw), def.family().orElse(null), def.getUnit())
                .map(p -> Classification.numeric(storedScale(p.num()),
                                                 storedScale(p.min()),
                                                 storedScale(p.max())))
                .orElseGet(Classification::refused);
    }

    /**
     * The string as a number, but only when the number can reproduce the string exactly — package
     * private so the rule can be pinned by test.
     *
     * <p>⚠️ "0805" is why this is not simply {@code new BigDecimal(s)}. It is an imperial case code
     * living in a field with no unit family; read as 805 it loses both its value and its place in
     * the free-text search, so searching "0805" stopped finding the part. Date codes, ordering
     * suffixes and anything else whose leading zero is meaning rather than formatting behave the
     * same way. If the number cannot reproduce the string, we did not understand the string.
     */
    static BigDecimal numericIfLossless(String s) {
        BigDecimal plain = plainNumber(s);
        // Compared against the value as it will be READ BACK — valueOf strips trailing zeros, so
        // "1.50" would return as "1.5". Comparing the unstripped form instead would call that
        // lossless and quietly change the value the user sees.
        return plain != null && plain.stripTrailingZeros().toPlainString().equals(s) ? plain : null;
    }

    /**
     * Significant digits kept when a value is stored. Mirrors {@code clean()} in
     * {@code utils/units.ts}, which already truncates to 12 for display.
     */
    private static final java.math.MathContext STORED_PRECISION = new java.math.MathContext(12);

    /**
     * Round a value to {@link #STORED_PRECISION} — <b>the step that makes equality searches work</b>.
     *
     * <p>⚠️ The design assumed {@code NUMERIC} was enough to avoid the component cache's
     * {@code value_exact}/{@code value_num} split, on the grounds that only cc's source numbers are
     * JSON doubles. Ours are too: the catalogue's 100 nF capacitor arrives from the JSONB as
     * {@code 1.0000000000000001e-7}, so it is stored as {@code 0.00000010000000000000001} and
     * "capacitance = 100 nF" — the exact query this whole rewrite exists to enable — finds nothing.
     * NUMERIC preserves whatever it is given; it cannot un-ruin a number that was a double first.
     *
     * <p>Twelve significant digits is far beyond any real component tolerance (a 0.01% resistor has
     * four), so nothing measurable is lost, and it is the precision the frontend has always used.
     */
    private static BigDecimal storedScale(BigDecimal v) {
        return v == null ? null : v.round(STORED_PRECISION).stripTrailingZeros();
    }

    private static BigDecimal plainNumber(String s) {
        try {
            return new BigDecimal(MetricUnitParser.normalizeSpaces(s));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * A key no definition covers becomes one, in the organisation's default group.
     *
     * <p>The JSONB could hold a loose key indefinitely and let "rescan from parts" promote it later;
     * a row table needs a {@code spec_definition_id}, so the promotion happens at write time instead.
     * That is a small change in practice — only 7 keys in the whole catalogue currently lack a
     * definition — and the existing merge / alias / convert-to-number tooling is still the cleanup
     * path for whatever a source invents.
     *
     * <p>The type is inferred from the one value in hand, which is all this path has: a boolean
     * gives BOOLEAN, a number NUMBER, anything else TEXT. That is deliberately weaker than
     * {@code rescanFromParts}, which sees every value of a key at once and can spot a SELECT — and
     * it is why a rescan still earns its place. Getting it roughly right matters because the type
     * decides how the <em>next</em> value of that key is classified. No unit family is guessed:
     * an over-eager family is how a 4 KB memory becomes 4000.
     *
     * <p>The group is resolved from the <b>part's</b> organisation rather than through
     * {@code SpecGroupService.defaultGroup()}, which reads the request-scoped current organisation:
     * this method has to work from the bulk backfill too, where there is no request.
     */
    private SpecDefinition createDefinition(Organisation organisation, String jsonName, Object sample) {
        SpecDefinition def = SpecDefinition.builder()
                .organisation(organisation)
                .jsonName(jsonName)
                .name(SpecNameHumanizer.humanize(jsonName))
                .dataType(inferDataType(sample))
                .displayOrder(0)
                .group(defaultGroupFor(organisation))
                .build();
        log.debug("auto-created spec definition {} for organisation {}", jsonName, organisation.getId());
        return specRepo.save(def);
    }

    private static String inferDataType(Object sample) {
        if (sample instanceof Boolean) return "BOOLEAN";
        if (sample instanceof Number) return "NUMBER";
        String s = MetricUnitParser.normalizeSpaces(String.valueOf(sample));
        if ("true".equalsIgnoreCase(s) || "false".equalsIgnoreCase(s)) return "BOOLEAN";
        // Same losslessness rule as classify: "0805" is a code, not the number 805.
        return numericIfLossless(s) != null ? "NUMBER" : "TEXT";
    }

    private SpecGroup defaultGroupFor(Organisation organisation) {
        Long orgId = organisation.getId();
        return groupRepo.findByOrganisationIdAndNameIgnoreCase(orgId, DEFAULT_GROUP_NAME)
                .or(() -> groupRepo.findByOrganisationIdOrderByDisplayOrderAscNameAsc(orgId)
                        .stream().findFirst())
                .orElseGet(() -> groupRepo.save(SpecGroup.builder()
                        .organisation(organisation)
                        .name(DEFAULT_GROUP_NAME)
                        .displayOrder(0)
                        .build()));
    }
}
