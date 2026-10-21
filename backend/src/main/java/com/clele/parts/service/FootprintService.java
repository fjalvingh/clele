package com.clele.parts.service;

import com.clele.parts.dto.FootprintDTO;
import com.clele.parts.dto.FootprintRequest;
import com.clele.parts.model.Footprint;
import com.clele.parts.model.FootprintAlias;
import com.clele.parts.repository.FootprintRepository;
import com.clele.parts.repository.PartRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The organisation's list of footprints, and the one place that turns whatever a writer has in hand
 * into a name on it.
 *
 * <p>Two spellings are the same footprint when their {@link #matchKey match keys} are equal —
 * "SOT23", "sot-23" and "SOT 23" all key to {@code sot23} — or when one is an alias of the other.
 * The match key is enforced as unique across every name and alias of an organisation, so a lookup
 * can never land on two footprints.
 *
 * <p>Writers differ in how strict they are. The screens and the lookups {@link #canonicalize}: a
 * known spelling becomes its canonical name and an unknown one is added to the list, so the list
 * stays a complete record of what is in use. The MCP {@code add_part} tool {@link #match matches}
 * and refuses what it cannot place, because a model will happily invent a plausible package name
 * that nobody uses.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FootprintService {

    private static final int MAX_SIMILAR = 10;

    /** CJK scripts and full-width forms: the component cache's "插件" (through-hole) and the like. */
    private static final Pattern CJK = Pattern.compile(
            "[\\u3000-\\u303F\\u3040-\\u30FF\\u3400-\\u9FFF\\uAC00-\\uD7AF\\uF900-\\uFAFF\\uFF00-\\uFFEF]");

    /** "SMD,19.2x18mm": a mounting style and a body size, which no one can look a footprint up by. */
    private static final Pattern MOUNTING_AND_SIZE = Pattern.compile("^\\s*SMD\\s*,", Pattern.CASE_INSENSITIVE);

    /** Match keys of what distributors put in the package field when they have no package. */
    private static final Set<String> NOT_A_PACKAGE = Set.of(
            "smd", "smt", "tht", "throughhole", "thruhole", "plugin", "other", "others",
            "na", "none", "unknown", "custom");

    /** A trailing "(...)": the distributor habit of naming a second outline, "SMA(DO-214AC)". */
    private static final Pattern TRAILING_PARENTHESES = Pattern.compile("^(.*?)\\s*\\([^()]*\\)\\s*$");

    private final FootprintRepository footprintRepository;
    private final PartRepository partRepository;
    private final CurrentOrganisationService currentOrganisationService;

    /** What a lookup found: the canonical name, or the near misses when there is none. */
    public record Match(String name, List<String> similar) {
        public boolean found() {
            return name != null;
        }
    }

    /**
     * Lower case, letters and digits only. V64 and V65 compute the same key in SQL
     * ({@code lower(regexp_replace(x, '[^A-Za-z0-9]', '', 'g'))}); the two must agree. A spelling
     * with no letters or digits at all keys on itself, so "-" does not equal "/".
     */
    public static String matchKey(String spelling) {
        String key = spelling.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
        return key.isEmpty() ? spelling.trim() : key;
    }

    /**
     * Whether a spelling names a package at all. The component cache fills a large share of its
     * package fields with "插件" (through-hole), "SMD", "-" or "SMD,19.2x18mm"; stored, those
     * would flood the list with entries no order can use. V65's {@code fp_junk} and
     * {@code isPackageName} in the frontend's {@code utils/footprints.ts} are the same rule.
     */
    public static boolean isPackageName(String spelling) {
        if (spelling == null || CJK.matcher(spelling).find() || MOUNTING_AND_SIZE.matcher(spelling).find()) {
            return false;
        }
        String key = spelling.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
        return !key.isEmpty() && !NOT_A_PACKAGE.contains(key);
    }

    public List<FootprintDTO> findAll() {
        Long orgId = currentOrganisationService.currentId();
        Map<String, Long> counts = new HashMap<>();
        for (Object[] row : partRepository.countByFootprint(orgId)) {
            counts.put((String) row[0], (Long) row[1]);
        }
        return footprintRepository.findByOrganisationIdOrderByNameAsc(orgId).stream()
                .map(f -> toDTO(f, counts.getOrDefault(f.getName(), 0L)))
                .toList();
    }

    /**
     * The canonical name for a spelling, if the list knows it. "SMA(DO-214AC)" that the list does
     * not know is looked up again as "SMA".
     */
    public Match match(String spelling) {
        if (!isPackageName(spelling)) {
            return new Match(null, List.of());
        }
        String key = matchKey(spelling);
        Map<String, Footprint> index = index(currentOrganisationService.currentId());
        Footprint hit = index.get(key);
        Matcher parentheses = TRAILING_PARENTHESES.matcher(spelling);
        if (hit == null && parentheses.matches() && !parentheses.group(1).isBlank()) {
            hit = index.get(matchKey(parentheses.group(1)));
        }
        if (hit != null) {
            return new Match(hit.getName(), List.of());
        }
        return new Match(null, similar(index, key));
    }

    /**
     * The name to store for a spelling: the canonical one when the list knows it, otherwise the
     * spelling itself, added to the list. Null, blank and what is {@link #isPackageName not a
     * package} become null.
     */
    @Transactional
    public String canonicalize(String spelling) {
        if (!isPackageName(spelling)) {
            return null;
        }
        Match match = match(spelling);
        if (match.found()) {
            return match.name();
        }
        String name = spelling.trim();
        footprintRepository.save(Footprint.builder()
                .organisation(currentOrganisationService.current())
                .name(name)
                .build());
        return name;
    }

    @Transactional
    public FootprintDTO create(FootprintRequest request) {
        requirePackageName(request.getName());
        Map<String, Footprint> index = index(currentOrganisationService.currentId());
        Footprint footprint = Footprint.builder()
                .organisation(currentOrganisationService.current())
                .name(request.getName().trim())
                .description(blankToNull(request.getDescription()))
                .build();
        setAliases(footprint, request.getAliases() == null ? List.of() : request.getAliases());
        requireUniqueKeys(index, footprint);
        return toDTO(footprintRepository.save(footprint), 0);
    }

    /**
     * Change a footprint. A rename moves every part carrying the old name along with it, and keeps
     * the old name as an alias so a source that still says it lands here.
     */
    @Transactional
    public FootprintDTO update(Long id, FootprintRequest request) {
        requirePackageName(request.getName());
        Long orgId = currentOrganisationService.currentId();
        Footprint footprint = require(id);
        // Read before anything changes: the query would otherwise flush the half-edited footprint,
        // and a clashing alias would surface as a constraint violation instead of a message.
        Map<String, Footprint> index = index(orgId);
        String oldName = footprint.getName();
        String newName = request.getName().trim();

        List<String> aliases = request.getAliases() != null
                ? new ArrayList<>(request.getAliases())
                : new ArrayList<>(footprint.getAliases().stream().map(FootprintAlias::getAlias).toList());
        if (!newName.equals(oldName) && !matchKey(newName).equals(matchKey(oldName))) {
            aliases.add(oldName);
        }
        footprint.setName(newName);
        footprint.setDescription(blankToNull(request.getDescription()));
        setAliases(footprint, aliases);
        requireUniqueKeys(index, footprint);

        Footprint saved = footprintRepository.save(footprint);
        if (!newName.equals(oldName)) {
            partRepository.renameFootprint(orgId, oldName, newName);
        }
        return toDTO(saved, partRepository.countByOrganisationIdAndFootprint(orgId, newName));
    }

    /**
     * Fold one footprint into another: its parts move to the target, and its name and aliases
     * become the target's aliases. This is how two spellings of one package that the match key
     * could not see as one ("SOT-23" and "TO-236") are put together after the fact.
     */
    @Transactional
    public FootprintDTO merge(Long sourceId, Long targetId) {
        if (sourceId.equals(targetId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A footprint cannot be merged into itself");
        }
        Long orgId = currentOrganisationService.currentId();
        Footprint source = require(sourceId);
        Footprint target = require(targetId);

        List<String> aliases = new ArrayList<>(target.getAliases().stream().map(FootprintAlias::getAlias).toList());
        aliases.add(source.getName());
        source.getAliases().forEach(a -> aliases.add(a.getAlias()));

        partRepository.renameFootprint(orgId, source.getName(), target.getName());
        footprintRepository.delete(source);
        footprintRepository.flush();

        setAliases(target, aliases);
        Footprint saved = footprintRepository.save(target);
        return toDTO(saved, partRepository.countByOrganisationIdAndFootprint(orgId, target.getName()));
    }

    /** Remove a footprint no part uses. One in use is merged, not deleted. */
    @Transactional
    public void delete(Long id) {
        Footprint footprint = require(id);
        long used = partRepository.countByOrganisationIdAndFootprint(
                currentOrganisationService.currentId(), footprint.getName());
        if (used > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, used + (used == 1 ? " part uses" : " parts use")
                    + " footprint " + footprint.getName() + "; merge it into another footprint instead");
        }
        footprintRepository.delete(footprint);
    }

    // ---------------------------------------------------------------- internals

    private Footprint require(Long id) {
        return footprintRepository.findByIdAndOrganisationId(id, currentOrganisationService.currentId())
                .orElseThrow(() -> new EntityNotFoundException("Footprint not found: " + id));
    }

    /** Every match key of the organisation — names and aliases — to the footprint it reaches. */
    private Map<String, Footprint> index(Long orgId) {
        Map<String, Footprint> index = new HashMap<>();
        for (Footprint f : footprintRepository.findByOrganisationIdOrderByNameAsc(orgId)) {
            index.putIfAbsent(matchKey(f.getName()), f);
            f.getAliases().forEach(a -> index.putIfAbsent(matchKey(a.getAlias()), f));
        }
        return index;
    }

    /**
     * Footprints one key contains the other of — "sot235l" finds SOT-23-5, "qfn163x3mm" finds
     * QFN-16 — longest (most specific) first.
     */
    private List<String> similar(Map<String, Footprint> index, String key) {
        Set<String> names = new LinkedHashSet<>();
        index.entrySet().stream()
                .filter(e -> e.getKey().length() >= 2 && (key.contains(e.getKey()) || e.getKey().contains(key)))
                .sorted(Comparator.comparingInt((Map.Entry<String, Footprint> e) -> -e.getKey().length())
                        .thenComparing(Map.Entry::getKey))
                .forEach(e -> names.add(e.getValue().getName()));
        return names.stream().limit(MAX_SIMILAR).toList();
    }

    /**
     * Replace the alias set, dropping blanks, duplicates and any alias that the match key would
     * find anyway (one equal to the footprint's own name).
     */
    private void setAliases(Footprint footprint, List<String> wanted) {
        String ownKey = matchKey(footprint.getName());
        Map<String, String> byKey = new LinkedHashMap<>();
        for (String alias : wanted) {
            if (alias == null || alias.isBlank()) continue;
            String trimmed = alias.trim();
            if (trimmed.length() > 64) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Alias '" + trimmed + "' is longer than 64 characters");
            }
            String key = matchKey(trimmed);
            if (!key.equals(ownKey)) byKey.putIfAbsent(key, trimmed);
        }
        Map<String, FootprintAlias> existing = new HashMap<>();
        footprint.getAliases().forEach(a -> existing.put(a.getAlias(), a));
        footprint.getAliases().removeIf(a -> !byKey.containsValue(a.getAlias()));
        for (String alias : byKey.values()) {
            if (!existing.containsKey(alias)) {
                footprint.getAliases().add(FootprintAlias.builder()
                        .footprint(footprint)
                        .organisation(footprint.getOrganisation())
                        .alias(alias)
                        .build());
            }
        }
    }

    /** No name or alias of this footprint may reach another one. */
    private static void requirePackageName(String name) {
        if (!isPackageName(name)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "'" + name.trim() + "' is a mounting style, not a package type");
        }
    }

    private void requireUniqueKeys(Map<String, Footprint> index, Footprint footprint) {
        List<String> spellings = new ArrayList<>();
        spellings.add(footprint.getName());
        footprint.getAliases().forEach(a -> spellings.add(a.getAlias()));
        for (String spelling : spellings) {
            Footprint other = index.get(matchKey(spelling));
            if (other != null && !other.getId().equals(footprint.getId())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "'" + spelling
                        + "' already names footprint " + other.getName() + "; merge the two instead");
            }
        }
    }

    private static FootprintDTO toDTO(Footprint footprint, long partCount) {
        return FootprintDTO.builder()
                .id(footprint.getId())
                .name(footprint.getName())
                .description(footprint.getDescription())
                .aliases(footprint.getAliases().stream().map(FootprintAlias::getAlias).sorted().toList())
                .partCount(partCount)
                .build();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
