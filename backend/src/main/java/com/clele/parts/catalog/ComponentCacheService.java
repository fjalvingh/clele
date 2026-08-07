package com.clele.parts.catalog;

import com.clele.parts.catalog.ComponentCacheRepository.CcAttribute;
import com.clele.parts.catalog.ComponentCacheRepository.CcComponent;
import com.clele.parts.dto.ComponentCacheDetailDTO;
import com.clele.parts.dto.ComponentCacheMatchDTO;
import com.clele.parts.dto.ComponentCacheSpecDTO;
import com.clele.parts.dto.ComponentCacheStatusDTO;
import com.clele.parts.model.SpecDefinition;
import com.clele.parts.repository.SpecAliasRepository;
import com.clele.parts.repository.SpecDefinitionRepository;
import com.clele.parts.service.CurrentOrganisationService;
import com.clele.parts.service.MetricUnitParser;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The component cache as the rest of the app sees it: search for a part, then take one.
 *
 * <p>This is the only class that understands the {@code cc_*} snapshot's conventions — SI base
 * units, positional slots, {@code "NaN"} for absent, attribute names that are prose. Everything
 * outside it deals in ordinary {@link com.clele.parts.dto.PartDTO}-shaped fields and
 * {@code part.specs} keys.
 *
 * <p><b>Two calls, not one.</b> {@link #search} returns enough to recognise a part; {@link #load}
 * fetches the whole record for the one that was chosen. Splitting them is what keeps a result page
 * cheap: a hundred-attribute pivot for sixty candidates the user will read three of is work nobody
 * asked for.
 *
 * <p><b>Neither call writes.</b> The result is applied through the paths that already exist — Quick
 * Add's create, or {@code PartService.applyAiLookup} for a part that is already in the catalogue.
 * The cache is a source like the AI lookup and the datasheet reader, and sources propose.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ComponentCacheService {

    /** Results returned by a search. Enough to scan, few enough to read. */
    private static final int SEARCH_LIMIT = 15;

    /**
     * Attributes the component row already carries as a column, and which would otherwise arrive
     * twice — once as {@code part.manufacturer}/{@code footprint}, once as a specification.
     * Lower-cased for comparison against the cache's own capitalisation.
     */
    private static final Set<String> COLUMN_ATTRIBUTES =
            Set.of("manufacturer", "package", "basic/extended", "status");

    /**
     * Values that mean "the vendor did not state this". The cache renders them as {@code "-"} in
     * {@code display} and as the <em>string</em> {@code "NaN"} in {@code value_text} — note that
     * {@code 'NaN'::float8} is a valid float, so an unfiltered value poisons every later comparison.
     */
    private static final Set<String> ABSENT = Set.of("-", "nan", "n/a", "");

    private final ComponentCacheRepository repository;
    private final SpecDefinitionRepository specDefinitionRepository;
    private final SpecAliasRepository specAliasRepository;
    private final CurrentOrganisationService currentOrganisationService;

    /** Whether the snapshot is installed, and how old it is. Safe to call without it. */
    public ComponentCacheStatusDTO status() {
        if (!repository.available()) {
            return ComponentCacheStatusDTO.builder().available(false).componentCount(0).build();
        }
        Map<String, String> meta = repository.importMeta();
        long count = 0;
        try {
            count = Long.parseLong(meta.getOrDefault("imported_components", "0"));
        } catch (NumberFormatException ignored) {
            // Provenance is informational; a malformed row must not break the status call.
        }
        return ComponentCacheStatusDTO.builder()
                .available(true)
                .componentCount(count)
                .snapshotDate(meta.get("manifest_created"))
                .source(meta.get("source"))
                .build();
    }

    /**
     * Parts in the cache matching a search term, best first.
     *
     * <p>Short terms are refused rather than answered badly: pg_trgm needs three characters to form
     * a trigram at all, so "LM" would fall through to full text alone and return whatever happened
     * to mention it.
     */
    public List<ComponentCacheMatchDTO> search(String term) {
        String q = term == null ? "" : term.trim();
        if (q.length() < 3 || !repository.available()) {
            return List.of();
        }
        List<CcComponent> rows = repository.search(q, SEARCH_LIMIT);
        Map<String, Integer> counts = repository.attributeCounts(rows.stream().map(CcComponent::lcsc).toList());
        return rows.stream().map(c -> ComponentCacheMatchDTO.builder()
                .lcsc(c.lcsc())
                .mpn(c.mpn())
                .manufacturer(c.manufacturer())
                .description(c.description())
                .packageName(blankToNull(c.packageName()))
                .category(c.category())
                .subcategory(c.subcategory())
                .basicExtended(c.basicExtended())
                .status(c.status())
                .stock(c.stock())
                .priceQty1(c.priceQty1())
                .datasheetUrl(c.datasheetUrl())
                .imageUrl(c.imageUrl())
                .productUrl(c.productUrl())
                .specCount(counts.getOrDefault(c.lcsc(), 0))
                .score(round(c.score()))
                .build()).toList();
    }

    /**
     * The whole cached record for one part, with its attributes translated into this app's spec
     * keys and values.
     *
     * <p>Read-only, but {@code @Transactional} because resolving keys reads the organisation's spec
     * definitions and aliases — two queries that should see one consistent picture.
     */
    @Transactional(readOnly = true)
    public ComponentCacheDetailDTO load(String lcsc) {
        CcComponent c = repository.findByLcsc(lcsc)
                .orElseThrow(() -> new EntityNotFoundException("Component not in cache: " + lcsc));

        SpecResolver resolver = new SpecResolver();
        Map<String, String> specs = new LinkedHashMap<>();
        List<ComponentCacheSpecDTO> attributes = new ArrayList<>();
        List<String> skipped = new ArrayList<>();

        for (CcAttribute attr : repository.attributes(lcsc)) {
            String name = attr.name() == null ? "" : attr.name();
            if (COLUMN_ATTRIBUTES.contains(name.toLowerCase(Locale.ROOT))) {
                skipped.add(name);
                continue;
            }
            String key = squash(name);
            if (key.isEmpty() || specs.containsKey(key)) {
                // Two source names can squash to one key ("Number of Channels" / "Numberof
                // Channels"). First wins; a second value for the same field is not a merge decision
                // this class is entitled to make.
                continue;
            }
            SpecDefinition def = resolver.definitionFor(key);
            String value = valueFor(attr, def);
            if (value == null) {
                skipped.add(name);
                continue;
            }
            String canonical = def != null ? def.getJsonName() : key;
            specs.put(canonical, value);
            attributes.add(ComponentCacheSpecDTO.builder()
                    .key(canonical)
                    .sourceName(name)
                    .value(value)
                    .known(def != null)
                    .build());
        }

        return ComponentCacheDetailDTO.builder()
                .lcsc(c.lcsc())
                .mpn(c.mpn())
                .manufacturer(c.manufacturer())
                .description(c.description())
                .footprint(blankToNull(c.packageName()))
                .category(c.category())
                .subcategory(c.subcategory())
                .basicExtended(c.basicExtended())
                .status(c.status())
                .stock(c.stock())
                .joints(c.joints())
                .priceQty1(c.priceQty1())
                .priceMin(c.priceMin())
                .datasheetUrl(c.datasheetUrl())
                .imageUrl(c.imageUrl())
                .productUrl(c.productUrl())
                .specs(specs)
                .attributes(attributes)
                .skipped(skipped)
                .build();
    }

    // ── Value translation ────────────────────────────────────────────────────

    /**
     * What to store for one attribute, or null when there is nothing to store.
     *
     * <p><b>{@code display} is the default and the numeric reading is the exception.</b> That is the
     * opposite of what the schema invites, and it is deliberate: {@code display} is the vendor's own
     * rendering with its unit attached ("2.2 MHz", "4 KB", "-40.0 °C ~ 125.0 °C"), so it can be
     * imprecise but never wrong about magnitude. A bare number is only written when this app has a
     * field whose declared unit provably reconciles with the attribute's SI family — everywhere else
     * the two conventions could differ by three orders of magnitude with nothing to signal it.
     *
     * <p>Three conditions gate the numeric path, and each one has a failure it prevents:
     * <ul>
     *   <li><b>a single slot</b> — "1 V ~ 18 V, -18 V ~ -1 V" has four, and its {@code value_num} is
     *       whichever the vendor happened to list first. Storing 1 as "supply voltage" is a lie the
     *       user cannot see.</li>
     *   <li><b>a NUMBER definition</b> — a TEXT or SELECT field wants the readable form.</li>
     *   <li><b>a reconcilable unit</b> — "mm" against the {@code length} family scales by 1000;
     *       "°C" against it does not reconcile at all and must fall back.</li>
     * </ul>
     */
    private String valueFor(CcAttribute attr, SpecDefinition def) {
        String display = trimToNull(attr.display());
        String text = trimToNull(attr.valueText());
        if (isAbsent(display) && isAbsent(text)) {
            return null;
        }

        if (CcUnits.isTextual(attr.unitFamily())) {
            // display carries every slot ("Watchdog, LIN, IrDA"); value_text only the first.
            String value = isAbsent(display) ? text : display;
            return def != null && "BOOLEAN".equals(def.getDataType()) ? asBoolean(value) : value;
        }

        String numeric = numericFor(attr, def);
        return numeric != null ? numeric : (isAbsent(display) ? text : display);
    }

    /** The bare number for a NUMBER field whose unit reconciles with the attribute's family, else null. */
    private String numericFor(CcAttribute attr, SpecDefinition def) {
        if (def == null || !"NUMBER".equals(def.getDataType())
                || attr.slotCount() != 1 || attr.valueExact() == null) {
            return null;
        }
        Double raw = attr.valueNum();
        if (raw != null && (raw.isNaN() || raw.isInfinite())) {
            return null;
        }
        String unit = trimToNull(def.getUnit());

        // No declared unit: only families whose numbers are never SI-prefixed can be stored bare.
        if (unit == null) {
            return CcUnits.isScaleFree(attr.unitFamily()) ? plain(attr.valueExact()) : null;
        }
        // A multi-unit spec renders its own unit selector and stores "<number> <unit>"; that is a
        // different storage contract and not one worth guessing at from here.
        if (unit.contains(",")) {
            return null;
        }
        String siBase = CcUnits.siBase(attr.unitFamily());
        if (siBase == null) {
            return null;
        }
        Optional<Double> factor = MetricUnitParser.factorToBase(unit, siBase);
        if (factor.isEmpty() || factor.get() == 0d) {
            return null;
        }
        return plain(attr.valueExact().divide(BigDecimal.valueOf(factor.get()), java.math.MathContext.DECIMAL64));
    }

    /**
     * Resolves an attribute key onto this organisation's spec definitions, directly or through an
     * alias.
     *
     * <p>Loaded once per {@link #load} call rather than queried per attribute: a part carries up to
     * a few dozen attributes and the definition list is a few hundred rows.
     */
    private final class SpecResolver {
        private final Map<String, SpecDefinition> byKey = new LinkedHashMap<>();

        private SpecResolver() {
            Long orgId = currentOrganisationService.currentId();
            for (SpecDefinition def : specDefinitionRepository
                    .findByOrganisationIdOrderByDisplayOrderAscNameAsc(orgId)) {
                byKey.put(def.getJsonName(), def);
            }
            // Aliases second so a canonical name always beats an alias pointing elsewhere.
            specAliasRepository.findByOrganisationIdOrderByJsonNameAsc(orgId).forEach(alias ->
                    byKey.putIfAbsent(alias.getJsonName(), alias.getSpecDefinition()));
        }

        SpecDefinition definitionFor(String key) {
            return byKey.get(key);
        }
    }

    // ── Small helpers ────────────────────────────────────────────────────────

    /**
     * An attribute name reduced to a {@code part.specs} key: lower-cased, non-alphanumerics dropped.
     * "Gain Bandwidth Product" becomes {@code gainbandwidthproduct}, which is the shape every key in
     * this app already has (they came from Partsbox and OctoPart the same way).
     *
     * <p>A key that matches no definition is kept anyway — that is how the catalogue grows, since
     * "Rescan from parts" promotes surviving unknown keys into real fields.
     */
    private static String squash(String name) {
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            if (Character.isLetterOrDigit(ch)) {
                sb.append(Character.toLowerCase(ch));
            }
        }
        return sb.toString();
    }

    private static String asBoolean(String value) {
        String v = value.trim().toLowerCase(Locale.ROOT);
        if (v.equals("yes") || v.equals("true")) return "true";
        if (v.equals("no") || v.equals("false")) return "false";
        return value;
    }

    private static boolean isAbsent(String value) {
        return value == null || ABSENT.contains(value.trim().toLowerCase(Locale.ROOT));
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static String blankToNull(String s) {
        String t = trimToNull(s);
        // The cache writes a literal "-" where a part has no package.
        return t == null || t.equals("-") ? null : t;
    }

    private static double round(double score) {
        return Math.round(score * 1000d) / 1000d;
    }
}
