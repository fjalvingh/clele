package com.clele.parts.imports;

import com.clele.parts.model.Location;
import com.clele.parts.model.Part;
import com.clele.parts.model.StockEntry;
import com.clele.parts.model.StockMovement;
import com.clele.parts.model.AttachmentType;
import com.clele.parts.repository.LocationRepository;
import com.clele.parts.repository.PartAttachmentRepository;
import com.clele.parts.repository.PartRepository;
import com.clele.parts.repository.StockEntryRepository;
import com.clele.parts.repository.StockMovementRepository;
import com.clele.parts.service.PartAttachmentService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Imports a Partsbox WebSocket capture (data.txt, Transit+JSON) into Clele. Truncate-then-load:
 * wipes existing part data (keeps categories, spec definitions and locations) and reloads every
 * part with its enriched fields (description, manufacturer, mpn, datasheet, Octopart specs),
 * preserving each stock transaction as a {@link StockMovement} and caching the per-part/location
 * on-hand total in {@link StockEntry}. Images are downloaded in a second phase.
 */
@Service
@RequiredArgsConstructor
public class PartsboxImportService {

    private static final Logger log = LoggerFactory.getLogger(PartsboxImportService.class);
    private static final int MAX_IMAGES = 5;

    private final PartRepository partRepository;
    private final LocationRepository locationRepository;
    private final com.clele.parts.repository.AppUserRepository userRepository;
    private final StockEntryRepository stockEntryRepository;
    private final StockMovementRepository stockMovementRepository;
    private final PartAttachmentRepository partAttachmentRepository;
    private final PartAttachmentService partAttachmentService;
    private final PlatformTransactionManager txManager;

    public record ImportSummary(int parts, int movements, int stockEntries, int mergedNames,
                                int zeroStockParts, int imagesDownloaded, int imagesFailed) {
    }

    /** Result of the transactional load phase: counts + image URLs keyed by saved part id. */
    private record LoadResult(int parts, int movements, int stockEntries, int mergedNames,
                              int zeroStockParts, Map<Long, List<String>> imageUrlsByPartId) {
    }

    public ImportSummary importFile(Path file) throws Exception {
        log.info("Reading Partsbox capture from {}", file.toAbsolutePath());
        Map<String, List<Map<String, Object>>> tables = new PartsboxTransitReader().readInitialData(file);
        List<Map<String, Object>> parts = tables.getOrDefault("parts", List.of());
        List<Map<String, Object>> storages = tables.getOrDefault("storage", List.of());
        log.info("Decoded {} part rows and {} storage rows from capture", parts.size(), storages.size());

        // Phase 1: parts + stock in a single transaction.
        LoadResult load = new TransactionTemplate(txManager).execute(status -> loadData(parts, storages));

        // Phase 2: download images, each in its own transaction (PartAttachmentService.uploadFromUrl
        // is @Transactional), tolerating individual failures.
        int[] imageStats = downloadImages(load.imageUrlsByPartId());

        ImportSummary summary = new ImportSummary(load.parts(), load.movements(), load.stockEntries(),
                load.mergedNames(), load.zeroStockParts(), imageStats[0], imageStats[1]);
        log.info("Partsbox import complete: {}", summary);
        return summary;
    }

    private LoadResult loadData(List<Map<String, Object>> partRows, List<Map<String, Object>> storageRows) {
        wipePartData();
        Map<String, Location> storageById = loadLocations(storageRows);
        // The import runs without a logged-in user; attribute imported parts to the bootstrap admin.
        com.clele.parts.model.AppUser importUser = resolveImportUser();

        // Dedupe re-sent frames by part id, then group by name (= part_number) to merge duplicates.
        Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
        for (Map<String, Object> p : partRows) {
            String id = str(p, "part/id");
            if (id != null) {
                byId.putIfAbsent(id, p);
            }
        }
        Map<String, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        for (Map<String, Object> p : byId.values()) {
            String name = str(p, "part/name");
            if (name == null) {
                log.warn("Skipping part with no name: {}", p.get("part/id"));
                continue;
            }
            groups.computeIfAbsent(name, k -> new ArrayList<>()).add(p);
        }

        int partCount = 0, movementCount = 0, stockEntryCount = 0, mergedNames = 0, zeroStockParts = 0;
        Map<Long, List<String>> imageUrls = new LinkedHashMap<>();

        for (List<Map<String, Object>> members : groups.values()) {
            if (members.size() > 1) {
                mergedNames++;
            }
            Part part = buildMergedPart(members);
            part.setCreatedBy(importUser);
            partRepository.save(part);
            partCount++;

            List<String> images = collectImageUrls(members);
            if (!images.isEmpty()) {
                imageUrls.put(part.getId(), images);
            }

            Map<Long, Integer> qtyByLocation = new HashMap<>();
            Map<Long, BigDecimal> lastPriceByLocation = new HashMap<>();
            Map<Long, Long> lastPriceTsByLocation = new HashMap<>();

            for (Map<String, Object> member : members) {
                for (Object stockObj : asList(member.get("part/stock"))) {
                    if (!(stockObj instanceof Map<?, ?> raw)) {
                        continue;
                    }
                    @SuppressWarnings("unchecked")
                    Map<String, Object> stock = (Map<String, Object>) raw;
                    String storageId = str(stock, "stock/storage-id");
                    Location location = storageById.get(storageId);
                    if (location == null) {
                        log.warn("Part {} references unknown storage {}", part.getName(), storageId);
                        continue;
                    }
                    int quantity = intValue(stock.get("stock/quantity"));
                    BigDecimal price = money(stock.get("stock/price"));
                    long ts = longValue(stock.get("stock/timestamp"));

                    stockMovementRepository.save(StockMovement.builder()
                            .part(part)
                            .location(location)
                            .quantity(quantity)
                            .unitPrice(price)
                            .comments(str(stock, "stock/comments"))
                            .movedAt(toLocalDateTime(ts))
                            .createdBy(str(stock, "stock/user"))
                            .type(com.clele.parts.model.MovementType.IMPORT)
                            .build());
                    movementCount++;

                    qtyByLocation.merge(location.getId(), quantity, Integer::sum);
                    if (quantity > 0 && price != null && ts >= lastPriceTsByLocation.getOrDefault(location.getId(), 0L)) {
                        lastPriceByLocation.put(location.getId(), price);
                        lastPriceTsByLocation.put(location.getId(), ts);
                    }
                }
            }

            if (qtyByLocation.isEmpty()) {
                zeroStockParts++;
                continue;
            }
            for (Map.Entry<Long, Integer> loc : qtyByLocation.entrySet()) {
                stockEntryRepository.save(StockEntry.builder()
                        .part(part)
                        .location(locationRepository.getReferenceById(loc.getKey()))
                        .quantity(loc.getValue())
                        .minimumQuantity(0)
                        .unitPrice(lastPriceByLocation.get(loc.getKey()))
                        .build());
                stockEntryCount++;
            }
        }

        log.info("Loaded {} parts, {} movements, {} stock entries ({} merged, {} zero-stock)",
                partCount, movementCount, stockEntryCount, mergedNames, zeroStockParts);
        return new LoadResult(partCount, movementCount, stockEntryCount, mergedNames, zeroStockParts, imageUrls);
    }

    private void wipePartData() {
        log.info("Wiping existing part data (keeping categories, spec definitions, locations)");
        stockMovementRepository.deleteAllInBatch();
        stockEntryRepository.deleteAllInBatch();
        partAttachmentRepository.deleteAllInBatch();
        partRepository.deleteAllInBatch();
    }

    /** The user that owns everything created by an import: the bootstrap admin, or any user. */
    private com.clele.parts.model.AppUser resolveImportUser() {
        return userRepository.findByEmail("admin@clele.local")
                .or(() -> userRepository.findAll().stream().findFirst())
                .orElseThrow(() -> new IllegalStateException("No user to own imported data"));
    }

    private Map<String, Location> loadLocations(List<Map<String, Object>> storageRows) {
        // Imported locations are owned by the bootstrap admin (the import runs without a
        // logged-in user). Locations now require an owner.
        com.clele.parts.model.AppUser owner = resolveImportUser();
        Map<String, Location> byId = new HashMap<>();
        for (Map<String, Object> s : storageRows) {
            String id = str(s, "storage/id");
            String name = str(s, "storage/name");
            if (id == null || name == null) {
                continue;
            }
            Location location = locationRepository.findByName(name)
                    .orElseGet(() -> locationRepository.save(
                            Location.builder().name(name).description(str(s, "storage/description"))
                                    .owner(owner).build()));
            byId.put(id, location);
        }
        return byId;
    }

    private Part buildMergedPart(List<Map<String, Object>> members) {
        Part part = new Part();
        String name = pick(members, m -> str(m, "part/name"));
        part.setPartNumber(name);
        part.setName(name);
        part.setDescription(pick(members, m -> firstNonBlank(
                str(m, "part/description"), str(octo(m), "main-description"), str(m, "linked/description"))));
        part.setManufacturer(pick(members, m -> firstNonBlank(
                str(m, "part/manufacturer"), str(m, "linked/manufacturer"))));
        part.setMpn(pick(members, m -> firstNonBlank(str(m, "part/mpn"), str(m, "linked/mpn"))));
        part.setFootprint(pick(members, m -> str(m, "part/footprint")));
        part.setOctopartId(pick(members, m -> str(m, "linked/octopart-id")));
        part.setDatasheetUrl(pick(members, this::firstDatasheet));
        part.setSpecs(pick(members, this::extractSpecs));
        return part;
    }

    /** Octopart specs map: each value is {v: x} or {minv,maxv}. Flatten to name -> display value. */
    private Map<String, Object> extractSpecs(Map<String, Object> member) {
        Object specs = octo(member) == null ? null : octo(member).get("specs");
        if (!(specs instanceof Map<?, ?> specMap) || specMap.isEmpty()) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : specMap.entrySet()) {
            if (!(e.getValue() instanceof Map<?, ?> v)) {
                continue;
            }
            Object value = v.get("v");
            if (value == null && (v.get("minv") != null || v.get("maxv") != null)) {
                value = v.get("minv") + ".." + v.get("maxv");
            }
            if (value != null) {
                out.put(String.valueOf(e.getKey()), value);
            }
        }
        return out.isEmpty() ? null : out;
    }

    private String firstDatasheet(Map<String, Object> member) {
        Map<String, Object> o = octo(member);
        if (o == null) {
            return null;
        }
        for (Object d : asList(o.get("datasheets"))) {
            if (d instanceof Map<?, ?> dm) {
                Object url = dm.get("url");
                if (url != null) {
                    return url.toString();
                }
            }
        }
        return null;
    }

    /** Octopart primary image + SnapMagic part pictures, deduped, capped at MAX_IMAGES. */
    private List<String> collectImageUrls(List<Map<String, Object>> members) {
        Set<String> urls = new LinkedHashSet<>();
        for (Map<String, Object> m : members) {
            Map<String, Object> o = octo(m);
            if (o != null && o.get("image") instanceof Map<?, ?> img && img.get("url") != null) {
                urls.add(img.get("url").toString());
            }
            if (m.get("linked/snapmagic-data") instanceof Map<?, ?> snap) {
                for (Object u : asList(snap.get("images"))) {
                    if (u != null) {
                        urls.add(u.toString());
                    }
                }
            }
        }
        return urls.stream().limit(MAX_IMAGES).toList();
    }

    private int[] downloadImages(Map<Long, List<String>> imageUrlsByPartId) {
        int downloaded = 0, failed = 0;
        for (Map.Entry<Long, List<String>> e : imageUrlsByPartId.entrySet()) {
            for (String url : e.getValue()) {
                try {
                    partAttachmentService.uploadFromUrl(e.getKey(), url, AttachmentType.PHOTO);
                    downloaded++;
                } catch (Exception ex) {
                    failed++;
                    log.warn("Image failed for part {} ({}): {}", e.getKey(), url, ex.getMessage());
                }
            }
        }
        log.info("Images: {} downloaded, {} failed", downloaded, failed);
        return new int[]{downloaded, failed};
    }

    // --- helpers -------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static Map<String, Object> octo(Map<String, Object> member) {
        Object o = member.get("linked/octopart-data");
        return o instanceof Map<?, ?> ? (Map<String, Object>) o : null;
    }

    private static <T> T pick(List<Map<String, Object>> members, Function<Map<String, Object>, T> fn) {
        for (Map<String, Object> m : members) {
            T v = fn.apply(m);
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    private static String str(Map<String, Object> map, String key) {
        if (map == null) {
            return null;
        }
        Object v = map.get(key);
        if (v == null) {
            return null;
        }
        String s = v.toString();
        return s.isBlank() ? null : s;
    }

    private static List<?> asList(Object o) {
        return o instanceof List<?> list ? list : List.of();
    }

    private static int intValue(Object o) {
        return o instanceof Number n ? n.intValue() : 0;
    }

    private static long longValue(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }

    private static BigDecimal money(Object o) {
        if (!(o instanceof Number n)) {
            return null;
        }
        if (o instanceof Double || o instanceof Float) {
            return BigDecimal.valueOf(n.doubleValue()).setScale(2, RoundingMode.HALF_UP);
        }
        return BigDecimal.valueOf(n.longValue()).setScale(2, RoundingMode.HALF_UP);
    }

    private static LocalDateTime toLocalDateTime(long epochMillis) {
        return Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalDateTime();
    }
}
