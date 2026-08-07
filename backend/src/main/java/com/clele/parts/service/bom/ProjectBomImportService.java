package com.clele.parts.service.bom;

import com.clele.parts.dto.BomImportLinePreviewDTO;
import com.clele.parts.dto.BomImportPreviewDTO;
import com.clele.parts.model.*;
import com.clele.parts.repository.PartRepository;
import com.clele.parts.repository.ProjectBomLineRepository;
import com.clele.parts.repository.ProjectBomRepository;
import com.clele.parts.service.CurrentOrganisationService;
import com.clele.parts.service.CurrentUserService;
import com.clele.parts.service.ProjectService;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Imports a BOM export into a project, merging it into whatever is already there.
 *
 * <p>A project holds exactly one BOM, and re-uploading a revised export <em>merges</em> rather than
 * replaces. That is the whole point: matching a hundred lines to catalogue parts is hours of work
 * spread over days, and a schematic revision must not throw it away. What the file says (designators,
 * value, footprint, quantity) is refreshed; what the user concluded (the matched part, "provided",
 * "excluded") is preserved.
 *
 * <p>Every import is a <b>dry run unless {@code commit} is true</b>. The merge deletes lines that
 * have left the schematic, so the user sees the counts first — the same shape as the convert-to-number
 * dry run on spec definitions.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProjectBomImportService {

    private final ProjectBomRepository bomRepository;
    private final ProjectBomLineRepository lineRepository;
    private final PartRepository partRepository;
    private final BomFileParser parser;
    private final BomColumnMapper columnMapper;
    private final ProjectService projectService;
    private final CurrentUserService currentUserService;
    private final CurrentOrganisationService currentOrganisationService;

    /** Only used to detach the stored lines for a dry run — see {@link #preview}. */
    private final EntityManager entityManager;

    /** A row of the uploaded file, read through the column mapping. */
    private record Incoming(int lineNo, String referenceKey, String designators, String value,
                            String footprint, String mpn, String manufacturer, String description,
                            String datasheetUrl, int quantity, boolean dnp,
                            Map<String, String> extra) {
    }

    /**
     * Reports what importing this file would do, writing nothing.
     *
     * <p><b>The dry run must detach the stored lines before touching them</b>, which is what
     * {@link #loadLines} does. The merge refreshes the loaded entities in place, and those are
     * managed: left attached, Hibernate's dirty checking flushes every "would update" and the
     * preview silently *is* the import. This was measured, not theorised — a preview moved a
     * matched line's value and set its review flag, in a method annotated {@code readOnly}.
     *
     * <p>{@code readOnly} alone is not enough, and this is worth knowing before anyone "simplifies"
     * it away: {@code spring.jpa.open-in-view} defaults to true, so the EntityManager is opened by
     * the OSIV interceptor and outlives the transaction — the flush mode the transaction set is
     * restored when it ends, with the dirty entities still in the persistence context. Detaching
     * does not depend on any of that. The annotation stays as a statement of intent and a second
     * line of defence.
     *
     * <p>Separate public methods rather than one with a flag because self-invocation would leave
     * the annotation inert — the caller has to cross the proxy for either to mean anything.
     */
    @Transactional(readOnly = true)
    public BomImportPreviewDTO preview(Long projectId, MultipartFile file,
                                       Map<String, String> mappingOverride) {
        return run(projectId, file, mappingOverride, false);
    }

    /** Performs the merge for real. */
    @Transactional
    public BomImportPreviewDTO commit(Long projectId, MultipartFile file,
                                      Map<String, String> mappingOverride) {
        return run(projectId, file, mappingOverride, true);
    }

    private BomImportPreviewDTO run(Long projectId, MultipartFile file,
                                    Map<String, String> mappingOverride, boolean commit) {
        Project project = projectService.requireOwnProject(projectId);

        byte[] data = read(file);
        BomFileParser.ParsedFile parsed = parser.parse(data);

        ProjectBom bom = bomRepository.findByProjectId(projectId).orElse(null);
        Map<BomColumnRole, String> mapping = resolveMapping(parsed, mappingOverride, bom);
        List<String> warnings = warningsFor(mapping, parsed);

        List<Incoming> incoming = readLines(parsed, mapping);
        List<ProjectBomLine> existing = loadLines(bom, commit);

        Merge merge = merge(incoming, existing, project);

        if (commit) {
            bom = commit(project, bom, data, file, mapping, merge);
        }

        return BomImportPreviewDTO.builder()
                .committed(commit)
                .mapping(asStringKeys(mapping))
                .headers(parsed.headers())
                .delimiter(describe(parsed.delimiter()))
                .warnings(warnings)
                .totalLines(incoming.size())
                .added(merge.added.size())
                .updated(merge.updated.size())
                .unchanged(merge.unchanged)
                .removed(merge.removed.size())
                .changed(merge.changed)
                .autoMatched(merge.autoMatched)
                .lines(merge.preview)
                .build();
    }

    /**
     * The BOM's stored lines, <b>detached when this is a dry run</b> so the merge's in-place
     * refresh of them can never reach the database. See {@link #preview} for why an annotation is
     * not enough on its own.
     */
    private List<ProjectBomLine> loadLines(ProjectBom bom, boolean commit) {
        if (bom == null) {
            return List.of();
        }
        List<ProjectBomLine> lines = lineRepository.findByBomIdWithPart(bom.getId());
        if (!commit) {
            lines.forEach(entityManager::detach);
        }
        return lines;
    }

    // ------------------------------------------------------------------
    // Mapping
    // ------------------------------------------------------------------

    /**
     * The mapping to read this file with: the user's correction if they sent one, else the mapping
     * the last import of this BOM used (when its columns are all still present — a re-export of the
     * same schematic normally keeps them), else a fresh guess from the headers.
     */
    private Map<BomColumnRole, String> resolveMapping(BomFileParser.ParsedFile parsed,
                                                      Map<String, String> override,
                                                      ProjectBom bom) {
        if (override != null && !override.isEmpty()) {
            return parseMapping(override, parsed.headers());
        }
        if (bom != null && bom.getColumnMapping() != null && !bom.getColumnMapping().isEmpty()) {
            Map<BomColumnRole, String> remembered = parseMappingLenient(bom.getColumnMapping(), parsed.headers());
            if (!remembered.isEmpty()) {
                return remembered;
            }
        }
        return columnMapper.detect(parsed.headers());
    }

    private Map<BomColumnRole, String> parseMapping(Map<String, String> raw, List<String> headers) {
        Map<BomColumnRole, String> mapping = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : raw.entrySet()) {
            if (entry.getValue() == null || entry.getValue().isBlank()) {
                continue;
            }
            BomColumnRole role;
            try {
                role = BomColumnRole.valueOf(entry.getKey().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Unknown BOM column role: " + entry.getKey());
            }
            if (!headers.contains(entry.getValue())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "The file has no column named \"" + entry.getValue() + "\"");
            }
            mapping.put(role, entry.getValue());
        }
        return mapping;
    }

    /** As {@link #parseMapping} but silently drops entries this file cannot honour. */
    private Map<BomColumnRole, String> parseMappingLenient(Map<String, String> raw, List<String> headers) {
        Map<BomColumnRole, String> mapping = new LinkedHashMap<>();
        raw.forEach((key, header) -> {
            if (header == null || !headers.contains(header)) {
                return;
            }
            try {
                mapping.put(BomColumnRole.valueOf(key.toUpperCase(Locale.ROOT)), header);
            } catch (IllegalArgumentException ignored) {
                // A role that no longer exists in the code: drop it and re-detect.
            }
        });
        return mapping;
    }

    private List<String> warningsFor(Map<BomColumnRole, String> mapping, BomFileParser.ParsedFile parsed) {
        List<String> warnings = new ArrayList<>();
        if (!mapping.containsKey(BomColumnRole.REFERENCES)) {
            warnings.add("No designator column was recognised. Lines will be identified by part "
                    + "number or value instead, so a later re-import may not line up as cleanly.");
        }
        if (!mapping.containsKey(BomColumnRole.QUANTITY)) {
            warnings.add(mapping.containsKey(BomColumnRole.REFERENCES)
                    ? "No quantity column was recognised; quantities are taken from the number of designators."
                    : "No quantity column was recognised, and no designators to count — every line is quantity 1.");
        }
        if (!mapping.containsKey(BomColumnRole.MPN) && !mapping.containsKey(BomColumnRole.VALUE)) {
            warnings.add("Neither a part number nor a value column was recognised — there is nothing "
                    + "to match these lines against. Check the column mapping.");
        }
        if (parsed.rows().isEmpty()) {
            warnings.add("The file has a header row but no data rows.");
        }
        return warnings;
    }

    // ------------------------------------------------------------------
    // Reading
    // ------------------------------------------------------------------

    private List<Incoming> readLines(BomFileParser.ParsedFile parsed, Map<BomColumnRole, String> mapping) {
        Set<String> claimed = new HashSet<>(mapping.values());
        List<Incoming> lines = new ArrayList<>();
        Set<String> usedKeys = new HashSet<>();

        int lineNo = 0;
        for (Map<String, String> row : parsed.rows()) {
            lineNo++;
            String designators = get(row, mapping, BomColumnRole.REFERENCES);
            String value = get(row, mapping, BomColumnRole.VALUE);
            String mpn = get(row, mapping, BomColumnRole.MPN);

            Map<String, String> extra = new LinkedHashMap<>();
            row.forEach((header, cell) -> {
                if (!claimed.contains(header) && cell != null) {
                    extra.put(header, cell);
                }
            });

            lines.add(new Incoming(
                    lineNo,
                    uniqueKey(designators, mpn, value, lineNo, usedKeys),
                    designators,
                    value,
                    get(row, mapping, BomColumnRole.FOOTPRINT),
                    mpn,
                    get(row, mapping, BomColumnRole.MANUFACTURER),
                    get(row, mapping, BomColumnRole.DESCRIPTION),
                    get(row, mapping, BomColumnRole.DATASHEET),
                    quantityOf(row, mapping, designators),
                    dnpOf(row, mapping),
                    extra.isEmpty() ? null : extra));
        }
        return lines;
    }

    /**
     * The line's merge key. Normally the normalised designators; a file without them falls back to
     * its MPN or value, and a duplicate of either gets a positional suffix — the column is unique
     * per BOM and NOT NULL, so a key must always be produced, even for a file that gives us little
     * to work with.
     */
    private String uniqueKey(String designators, String mpn, String value, int lineNo, Set<String> used) {
        String base = DesignatorKey.normalize(designators);
        if (base.isBlank()) {
            base = firstNonBlank(mpn, value);
            base = base == null ? "LINE " + lineNo : base.toUpperCase(Locale.ROOT);
        }
        if (base.length() > 500) {
            base = base.substring(0, 500);
        }
        String key = base;
        int suffix = 2;
        while (!used.add(key)) {
            key = base + " #" + suffix++;
        }
        return key;
    }

    private int quantityOf(Map<String, String> row, Map<BomColumnRole, String> mapping, String designators) {
        String raw = get(row, mapping, BomColumnRole.QUANTITY);
        if (raw != null) {
            try {
                // Some exports write "3 pcs" or a decimal quantity; take the leading integer.
                String digits = raw.trim().replaceFirst("^([0-9]+).*$", "$1");
                int parsed = Integer.parseInt(digits);
                if (parsed > 0) {
                    return parsed;
                }
            } catch (NumberFormatException ignored) {
                // Fall through to the designator count.
            }
        }
        int byDesignator = DesignatorKey.count(designators);
        return byDesignator > 0 ? byDesignator : 1;
    }

    private boolean dnpOf(Map<String, String> row, Map<BomColumnRole, String> mapping) {
        String header = mapping.get(BomColumnRole.DNP);
        return header != null && columnMapper.isDoNotPopulate(header, row.get(header));
    }

    private String get(Map<String, String> row, Map<BomColumnRole, String> mapping, BomColumnRole role) {
        String header = mapping.get(role);
        return header == null ? null : row.get(header);
    }

    // ------------------------------------------------------------------
    // Merge
    // ------------------------------------------------------------------

    /** What the merge decided, before anything is written. */
    private static class Merge {
        final List<ProjectBomLine> added = new ArrayList<>();
        final List<ProjectBomLine> updated = new ArrayList<>();
        final List<ProjectBomLine> removed = new ArrayList<>();
        final List<BomImportLinePreviewDTO> preview = new ArrayList<>();
        int unchanged;
        int changed;
        int autoMatched;
    }

    private Merge merge(List<Incoming> incoming, List<ProjectBomLine> existing, Project project) {
        Merge merge = new Merge();
        Long orgId = currentOrganisationService.currentId();

        // Pass 1 — pair on the designator key. This is the identity of a BOM line: C7 stays C7
        // across a revision even when its value changes.
        Map<String, ProjectBomLine> byKey = new LinkedHashMap<>();
        for (ProjectBomLine line : existing) {
            byKey.put(line.getReferenceKey(), line);
        }

        List<Incoming> unpaired = new ArrayList<>();
        Map<Incoming, ProjectBomLine> pairs = new LinkedHashMap<>();
        for (Incoming line : incoming) {
            ProjectBomLine match = byKey.remove(line.referenceKey());
            if (match != null) {
                pairs.put(line, match);
            } else {
                unpaired.add(line);
            }
        }

        // Pass 2 — a line whose designators moved, paired on value + footprint instead. Without
        // this, re-numbering the schematic would look like every part being deleted and re-added,
        // and every match would be lost.
        Map<String, ProjectBomLine> byValue = new LinkedHashMap<>();
        for (ProjectBomLine line : byKey.values()) {
            byValue.putIfAbsent(valueKey(line.getValue(), line.getFootprint()), line);
        }
        for (Incoming line : unpaired) {
            String key = valueKey(line.value(), line.footprint());
            ProjectBomLine match = key.isBlank() ? null : byValue.remove(key);
            if (match != null) {
                byKey.remove(match.getReferenceKey());
                pairs.put(line, match);
            }
        }

        for (Incoming line : incoming) {
            ProjectBomLine target = pairs.get(line);
            if (target == null) {
                merge.added.add(apply(line, new ProjectBomLine(), merge, orgId, true));
            } else {
                boolean moved = applyToExisting(line, target, merge, orgId);
                if (moved) {
                    merge.updated.add(target);
                } else {
                    merge.unchanged++;
                    merge.preview.add(preview("UNCHANGED", line, target));
                }
            }
        }

        // Anything left is no longer in the schematic.
        merge.removed.addAll(byKey.values());
        for (ProjectBomLine line : merge.removed) {
            merge.preview.add(BomImportLinePreviewDTO.builder()
                    .action("REMOVED")
                    .designators(line.getDesignators())
                    .value(line.getValue())
                    .footprint(line.getFootprint())
                    .mpn(line.getMpn())
                    .manufacturer(line.getManufacturer())
                    .quantity(line.getQuantity())
                    .dnp(line.isDnp())
                    .matchedPartNumber(line.getPart() != null ? line.getPart().getPartNumber() : null)
                    .build());
        }
        return merge;
    }

    /** Fills a brand-new line from the file and gives auto-match a go at it. */
    private ProjectBomLine apply(Incoming line, ProjectBomLine target, Merge merge, Long orgId,
                                 boolean isNew) {
        copyFileFields(line, target);
        target.setStatus(line.dnp() ? BomLineStatus.EXCLUDED : BomLineStatus.UNMATCHED);
        if (!line.dnp()) {
            autoMatch(orgId, line).ifPresent(part -> {
                target.setPart(part);
                target.setStatus(BomLineStatus.MATCHED);
                target.setMatchSource(BomMatchSource.AUTO);
                merge.autoMatched++;
            });
        }
        if (isNew) {
            merge.preview.add(preview("ADDED", line, target));
        }
        return target;
    }

    /**
     * Refreshes an existing line from the file while preserving the decision recorded on it.
     * Returns whether anything actually moved.
     *
     * <p>Three rules earn their keep here:
     * <ul>
     *   <li>A {@code MATCHED} or {@code PROVIDED} line whose value or footprint moved is flagged
     *       {@code changed} — the part it points at may no longer be the right one, and silently
     *       keeping the match would hide that.</li>
     *   <li>An {@code UNMATCHED} line gets another go at auto-match: the catalogue may have gained
     *       the part since the last import.</li>
     *   <li>The DNP flag follows the file in both directions, but only over a line carrying no
     *       user decision — un-excluding a line the user excluded by hand would overrule them.</li>
     * </ul>
     */
    private boolean applyToExisting(Incoming line, ProjectBomLine target, Merge merge, Long orgId) {
        boolean valueMoved = !Objects.equals(line.value(), target.getValue())
                || !Objects.equals(line.footprint(), target.getFootprint());
        boolean anythingMoved = valueMoved
                || !Objects.equals(line.referenceKey(), target.getReferenceKey())
                || !Objects.equals(line.designators(), target.getDesignators())
                || !Objects.equals(line.mpn(), target.getMpn())
                || !Objects.equals(line.manufacturer(), target.getManufacturer())
                || !Objects.equals(line.description(), target.getDescription())
                || !Objects.equals(line.datasheetUrl(), target.getDatasheetUrl())
                || line.quantity() != target.getQuantity()
                || line.dnp() != target.isDnp()
                || line.lineNo() != target.getLineNo();

        boolean wasDnp = target.isDnp();
        boolean decided = target.getStatus() == BomLineStatus.MATCHED
                || target.getStatus() == BomLineStatus.PROVIDED;

        copyFileFields(line, target);

        if (valueMoved && decided) {
            target.setChanged(true);
            merge.changed++;
        }

        if (line.dnp() != wasDnp && !decided) {
            target.setStatus(line.dnp() ? BomLineStatus.EXCLUDED : BomLineStatus.UNMATCHED);
        }

        if (target.getStatus() == BomLineStatus.UNMATCHED && target.getPart() == null) {
            autoMatch(orgId, line).ifPresent(part -> {
                target.setPart(part);
                target.setStatus(BomLineStatus.MATCHED);
                target.setMatchSource(BomMatchSource.AUTO);
                merge.autoMatched++;
            });
        }

        if (anythingMoved) {
            merge.preview.add(preview("UPDATED", line, target));
        }
        return anythingMoved;
    }

    private void copyFileFields(Incoming line, ProjectBomLine target) {
        target.setLineNo(line.lineNo());
        target.setReferenceKey(line.referenceKey());
        target.setDesignators(line.designators());
        target.setValue(line.value());
        target.setFootprint(line.footprint());
        target.setMpn(line.mpn());
        target.setManufacturer(line.manufacturer());
        target.setDescription(line.description());
        target.setDatasheetUrl(line.datasheetUrl());
        target.setQuantity(line.quantity());
        target.setDnp(line.dnp());
        target.setExtra(line.extra());
    }

    /**
     * Matches a line to a part only on an <b>exact, unambiguous</b> hit — the MPN first, then the
     * value, against both {@code part_number} and {@code mpn}, case-insensitively.
     *
     * <p>Nothing fuzzy is accepted here. A trigram-similar part number is offered as a suggestion on
     * the matching screen instead: two part numbers that differ by a package suffix are similar
     * enough to auto-accept and different enough to be the wrong part, which is exactly how the
     * datasheet re-sourcing work attached a hex inverter's datasheet to four counters.
     */
    private Optional<Part> autoMatch(Long orgId, Incoming line) {
        for (String term : new String[]{line.mpn(), line.value()}) {
            if (term == null || term.isBlank()) {
                continue;
            }
            Map<Long, Part> candidates = new LinkedHashMap<>();
            partRepository.findByOrganisationIdAndPartNumberIgnoreCase(orgId, term.trim())
                    .forEach(p -> candidates.put(p.getId(), p));
            partRepository.findByOrganisationIdAndMpnIgnoreCase(orgId, term.trim())
                    .forEach(p -> candidates.put(p.getId(), p));
            if (candidates.size() == 1) {
                return Optional.of(candidates.values().iterator().next());
            }
        }
        return Optional.empty();
    }

    private String valueKey(String value, String footprint) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return (value.trim() + " " + (footprint == null ? "" : footprint.trim()))
                .toLowerCase(Locale.ROOT);
    }

    private BomImportLinePreviewDTO preview(String action, Incoming line, ProjectBomLine target) {
        return BomImportLinePreviewDTO.builder()
                .action(action)
                .designators(line.designators())
                .value(line.value())
                .footprint(line.footprint())
                .mpn(line.mpn())
                .manufacturer(line.manufacturer())
                .quantity(line.quantity())
                .dnp(line.dnp())
                .matchKept(target.getPart() != null)
                .matchedPartNumber(target.getPart() != null ? target.getPart().getPartNumber() : null)
                .changed(target.isChanged())
                .build();
    }

    // ------------------------------------------------------------------
    // Commit
    // ------------------------------------------------------------------

    private ProjectBom commit(Project project, ProjectBom bom, byte[] data, MultipartFile file,
                              Map<BomColumnRole, String> mapping, Merge merge) {
        if (bom == null) {
            bom = ProjectBom.builder().project(project).build();
        }
        bom.setFilename(file.getOriginalFilename());
        bom.setContentType(file.getContentType());
        bom.setData(data);
        bom.setColumnMapping(asStringKeys(mapping));
        bom.setImportedAt(LocalDateTime.now());
        bom.setImportedBy(currentUserService.current());
        bom = bomRepository.save(bom);

        // Removals go first. A line that left the schematic frees its designator key, and although
        // the merge never hands that key to another line (a key still in use would have paired in
        // pass 1), deleting first keeps the unique constraint satisfied at every step regardless.
        if (!merge.removed.isEmpty()) {
            lineRepository.deleteAll(merge.removed);
            lineRepository.flush();
        }

        for (ProjectBomLine line : merge.added) {
            line.setBom(bom);
        }
        lineRepository.saveAll(merge.added);
        lineRepository.saveAll(merge.updated);
        return bom;
    }

    private byte[] read(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No file was uploaded");
        }
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Failed to read file: " + e.getMessage());
        }
    }

    private Map<String, String> asStringKeys(Map<BomColumnRole, String> mapping) {
        Map<String, String> out = new LinkedHashMap<>();
        mapping.forEach((role, header) -> out.put(role.name(), header));
        return out;
    }

    private String describe(char delimiter) {
        return delimiter == '\t' ? "tab" : String.valueOf(delimiter);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }
}
