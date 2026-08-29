package com.clele.parts.service;

import com.clele.parts.repository.PartRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Re-reads every part's spec values under the current classification rules.
 *
 * <h2>Why it exists</h2>
 *
 * A value is classified once, when it is written. Everything that decides the outcome can change
 * afterwards — a definition gains a unit family, its data type is corrected, the parser learns a
 * spelling — and none of that reaches the values already stored. Re-syncing a part reads its
 * current values back out and writes them again through the one write path, so they land where
 * today's rules say they belong.
 *
 * <p><b>It is a dry run by default, and that is not politeness.</b> Since the data type became
 * authoritative, a NUMBER field's unreadable value is dropped rather than kept as text — so a field
 * that is really text but declared NUMBER ({@code "2K x 8"}, {@code "0805"}) loses its values here,
 * in bulk. The dry run is how that list is seen before it happens, and it is produced by the same
 * code path as the commit ({@code PartSpecValueService.preview}), so it cannot describe a different
 * outcome than the one that follows.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SpecResyncService {

    private final PartRepository partRepository;
    private final PartSpecValueService partSpecValueService;

    /**
     * @param dropped      how many values each spec key would lose, worst first
     * @param samples      up to a few distinct {@code key=value} pairs per key, to judge them by
     */
    public record Report(boolean committed, int parts, int scalars, int ranges, int texts,
                         int refused, Map<String, Integer> dropped, Map<String, List<String>> samples) {}

    private static final int SAMPLES_PER_KEY = 5;

    public Report run(boolean commit, int limit) {
        // Ids, not entities: each part is re-read inside its own transaction, so nothing here holds
        // a session open across the whole catalogue.
        List<Long> ids = partRepository.findAll().stream().map(p -> p.getId()).toList();
        if (limit > 0 && ids.size() > limit) ids = ids.subList(0, limit);

        int scalars = 0, ranges = 0, texts = 0, refused = 0, done = 0;
        Map<String, Integer> dropped = new LinkedHashMap<>();
        Map<String, List<String>> samples = new LinkedHashMap<>();

        for (Long partId : ids) {
            PartSpecValueService.SyncResult result = partSpecValueService.resync(partId, commit);

            scalars += result.scalars();
            ranges += result.ranges();
            texts += result.texts();
            refused += result.refused();
            for (String entry : result.unparsed()) {
                int eq = entry.indexOf('=');
                String key = eq < 0 ? entry : entry.substring(0, eq);
                dropped.merge(key, 1, Integer::sum);
                List<String> seen = samples.computeIfAbsent(key, k -> new ArrayList<>());
                if (seen.size() < SAMPLES_PER_KEY && !seen.contains(entry)) seen.add(entry);
            }

            if (++done % 250 == 0) log.info("spec-resync {}/{} parts", done, ids.size());
        }

        Map<String, Integer> ordered = new LinkedHashMap<>();
        dropped.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .forEach(e -> ordered.put(e.getKey(), e.getValue()));

        return new Report(commit, done, scalars, ranges, texts, refused, ordered, samples);
    }
}
