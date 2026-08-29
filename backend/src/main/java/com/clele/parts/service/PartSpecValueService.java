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
 * <ol>
 *   <li><b>A range string</b> — {@code "3..16"}, {@code "4.5..null"} — becomes {@code value_min}/
 *       {@code value_max}, either bound open. These are the ~1,500 Partsbox ranges that are dead as
 *       numbers today: convert-to-number has to refuse them, and no query can reach inside them.</li>
 *   <li><b>A three-part range string</b> — {@code "4.5..5..5.5"} — is {@code min..nominal..max}, and
 *       fills {@code value_num} <em>and</em> the bounds (V56). It is how the UI writes a value the
 *       user gave a tolerance band to, and how a datasheet's min/typ/max survives as one value.
 *       Any component may be {@code "null"}, so a nominal with only an upper bound is
 *       {@code "null..5..5.5"}.</li>
 *   <li><b>A JSON number</b> is stored as {@code value_num} <em>whatever the family</em>. No
 *       conversion happens, so there is no magnitude to get wrong — the number is already in
 *       whatever unit the field means, and comparing it with its siblings is exactly as valid as it
 *       was in the JSONB. This is what keeps the catalogue's 11,384 bare numbers numeric.</li>
 *   <li><b>A string with a unit family</b> is parsed by {@link MetricUnitParser} into the family's
 *       base SI unit — {@code "100nF"}, {@code "0.1 uF"} and {@code "1e-7"} all land on the same
 *       number, where as strings they are three unrelated values.</li>
 *   <li><b>Anything else</b> — no family, or a string the family refused — stays {@code value_text}.
 *       Nothing was extracted from it, so nothing can drift. A refusal is an ordinary outcome, not
 *       an error: it is how {@code 4m7} in a resistance field, or a value in a unit nobody declared,
 *       declines to become a wrong number.</li>
 * </ol>
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
    public record SyncResult(int scalars, int ranges, int texts, int definitionsCreated,
                             List<String> unparsed) {
        public int total() {
            return scalars + ranges + texts;
        }

        /** A part that vanished between listing and syncing — an empty outcome, not an error. */
        static SyncResult empty() {
            return new SyncResult(0, 0, 0, 0, List.of());
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
        Map<String, Object> specs = incoming == null ? Map.of() : incoming;
        Long orgId = part.getOrganisation().getId();

        Map<Long, PartSpecValue> existing = new HashMap<>();
        for (PartSpecValue v : valueRepo.findByPartId(part.getId())) {
            existing.put(v.getSpecDefinition().getId(), v);
        }

        int scalars = 0, ranges = 0, texts = 0, created = 0;
        List<String> unparsed = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        List<PartSpecValue> toSave = new ArrayList<>();

        for (Map.Entry<String, Object> entry : specs.entrySet()) {
            String key = entry.getKey();
            Object raw = entry.getValue();
            if (key == null || key.isBlank() || raw == null || String.valueOf(raw).isBlank()) continue;

            SpecDefinition def = specRepo.findByOrganisationIdAndJsonName(orgId, key).orElse(null);
            if (def == null) {
                def = createDefinition(part.getOrganisation(), key, raw);
                created++;
            }
            if (!seen.add(def.getId())) continue;   // two aliases of one spec on the same part

            PartSpecValue row = existing.get(def.getId());
            if (row == null) row = PartSpecValue.text(part, def, null);

            Classification c = classify(raw, def);
            switch (c.shape()) {
                case NUMERIC -> {
                    row.setNumeric(c.num(), c.min(), c.max());
                    // Counted for the backfill report only: a value with bounds is reported as a
                    // range whether or not it also carries a nominal.
                    if (c.hasBounds()) ranges++; else scalars++;
                }
                case TEXT -> {
                    row.setText(MetricUnitParser.normalizeSpaces(String.valueOf(raw)));
                    texts++;
                    if (c.wanted()) unparsed.add(key + "=" + raw);
                }
            }
            toSave.add(row);
        }

        // Keys the part no longer carries lose their row: the map is authoritative.
        List<PartSpecValue> stale = existing.entrySet().stream()
                .filter(e -> !seen.contains(e.getKey()))
                .map(Map.Entry::getValue)
                .toList();

        if (!stale.isEmpty()) valueRepo.deleteAll(stale);
        if (!toSave.isEmpty()) valueRepo.saveAll(toSave);

        // The search projection the Parts free-text index covers. Written here because this is the
        // only path that writes a spec value, so it cannot fall behind the rows it summarises.
        part.setSpecText(specTextOf(toSave));

        return new SyncResult(scalars, ranges, texts, created, unparsed);
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

    /** An open bound is written "null", which is the form Partsbox sent and the parser reads back. */
    private static String bound(BigDecimal b) {
        return b == null ? "null" : b.stripTrailingZeros().toPlainString();
    }

    /** Drop a part's rows outright — for the paths that delete a part's specs wholesale. */
    @Transactional
    public void deleteForPart(Long partId) {
        valueRepo.deleteByPartId(partId);
    }

    /**
     * Parsed or raw. The parsed shape covers a bare nominal, bounds, and both together — they are
     * one shape in the row too, since V56 relaxed the check constraint that kept them apart.
     */
    private enum Shape { NUMERIC, TEXT }

    /**
     * @param wanted true when this value <em>should</em> have parsed — a string against a field that
     *               declares a family — and did not. That is the residue worth eyeballing; a value
     *               with no family was never a candidate and is not a failure.
     */
    private record Classification(Shape shape, BigDecimal num, BigDecimal min, BigDecimal max,
                                  boolean wanted) {
        static Classification scalar(BigDecimal v) { return new Classification(Shape.NUMERIC, v, null, null, false); }
        static Classification range(BigDecimal lo, BigDecimal hi) { return new Classification(Shape.NUMERIC, null, lo, hi, false); }
        static Classification numeric(BigDecimal v, BigDecimal lo, BigDecimal hi) {
            return new Classification(Shape.NUMERIC, v, lo, hi, false);
        }
        static Classification text(boolean wanted) { return new Classification(Shape.TEXT, null, null, null, wanted); }

        boolean hasBounds() { return min != null || max != null; }
    }

    private Classification classify(Object raw, SpecDefinition def) {
        if ("BOOLEAN".equals(def.getDataType())) return Classification.text(false);

        // A JSON number needs no parsing and no family: nothing is converted, so nothing can be
        // wrong about its magnitude that was not already wrong in the JSONB.
        if (raw instanceof Number n) {
            return Classification.scalar(storedScale(new BigDecimal(n.toString())));
        }

        String s = MetricUnitParser.normalizeSpaces(String.valueOf(raw));
        Optional<UnitFamily> family = def.family();

        String[] bounds = splitRange(s);
        if (bounds != null && bounds.length == 3) {
            // "min..nominal..max" — the UI's spelling for a value with a tolerance band.
            BigDecimal lo = parseBound(bounds[0], family);
            BigDecimal nom = parseBound(bounds[1], family);
            BigDecimal hi = parseBound(bounds[2], family);
            if (nom != null || lo != null || hi != null) return Classification.numeric(nom, lo, hi);
            return Classification.text(family.isPresent());
        }
        if (bounds != null) {
            BigDecimal lo = parseBound(bounds[0], family);
            BigDecimal hi = parseBound(bounds[1], family);
            if (lo != null || hi != null) return Classification.range(lo, hi);
            return Classification.text(family.isPresent());
        }

        if (family.isPresent()) {
            Optional<String> parsed = MetricUnitParser.parseToBase(s, family.get());
            if (parsed.isPresent()) return Classification.scalar(storedScale(new BigDecimal(parsed.get())));
            return Classification.text(true);
        }

        // No family: a numeric-looking string may be a number, or may be a code that happens to be
        // digits. Convert only when the conversion is LOSSLESS -- when the canonical rendering of the
        // parsed number is the original string, character for character.
        //
        // ⚠️ "0805" is why. It is an imperial case code stored in a family-less field, and reading it
        // as the number 805 both destroys the value and drops it out of the free-text search, so
        // searching "0805" stopped finding the part. The same applies to date codes, ordering suffixes
        // and anything else whose leading zero is meaning rather than formatting. If the string cannot
        // be reproduced from the number, we did not understand it and must not extract it.
        BigDecimal plain = numericIfLossless(s);
        return plain != null ? Classification.scalar(storedScale(plain)) : Classification.text(false);
    }

    /**
     * The ways a range is written in this catalogue's sources, or null when the value is not one.
     * Components come back untrimmed; any may be the word "null" (an open bound).
     *
     * <p>Two components mean {@code min..max}; <b>three mean {@code min..nominal..max}</b>, which is
     * how the UI writes a value that has both a typical figure and a band (V56). Only the dotted
     * form can carry three — the other two spellings come from outside sources that never write one.
     *
     * <ul>
     *   <li>{@code "3..16"} — Partsbox, and the bulk of the data (1,488 values).</li>
     *   <li>{@code "-40.0 °C ~ 105.0 °C"} — the component cache's own {@code display} rendering, so
     *       this form keeps arriving from a live source rather than only from the backlog.</li>
     *   <li>{@code "15 V to 35 V"} — how a datasheet writes it, and so how the extractor returns it.
     *       The spaces are required, or "to" would match inside a word.</li>
     * </ul>
     *
     * <p>A hyphen is deliberately <b>not</b> a separator: "-40-125" cannot be told from a negative
     * number, and guessing would invent bounds that were never written.
     */
    // Package-private so the separator rules can be pinned by test without a database.
    static String[] splitRange(String s) {
        // The dotted form splits into at most three, so "4.5..5..5.5" keeps its middle component
        // rather than handing "5..5.5" back as an unparseable upper bound.
        String[] dotted = s.split("\\.\\.", 3);
        if (dotted.length >= 2) return dotted;
        for (String sep : new String[]{"~", "(?i)\\s+to\\s+"}) {
            String[] parts = s.split(sep, 2);
            if (parts.length == 2) return parts;
        }
        return null;
    }

    /** One bound of a range; null when open ("null") or unparseable. */
    private static BigDecimal parseBound(String bound, Optional<UnitFamily> family) {
        String b = MetricUnitParser.normalizeSpaces(bound);
        if (b.isEmpty() || b.equalsIgnoreCase("null")) return null;
        if (family.isPresent()) {
            return MetricUnitParser.parseToBase(b, family.get()).map(BigDecimal::new)
                    .map(PartSpecValueService::storedScale).orElse(null);
        }
        return storedScale(plainNumber(b));
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
