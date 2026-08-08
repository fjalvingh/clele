package com.clele.parts.service;

import com.clele.parts.repository.PartRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Fills {@code part_spec_value} from the existing {@code part.specs} JSONB — step 3 of the typed
 * spec value migration (see {@code SPECS-REWRITE.md}).
 *
 * <p>It classifies every stored value through the same {@link PartSpecValueService} the intake paths
 * use, so the backlog and everything added from here are treated identically. Its other job is the
 * <b>report</b>: the values that <em>should</em> have parsed and did not, grouped by distinct value
 * the way convert-to-number's dry run groups its failures, so the residue can be eyeballed and
 * fixed by hand.
 *
 * <p><b>Dry run by default</b>, and the dry run really writes nothing — it goes through
 * {@link PartSpecValueService#preview}, which never touches a managed row (see the warning there).
 *
 * <p><b>Re-runnable rather than resumable.</b> {@code sync} is idempotent and involves no network,
 * so a second full pass costs seconds and converges on the same rows; there is nothing to skip and
 * no partial state to resume from. That is a weaker guarantee than the datasheet backfill needs
 * only because nothing here can half-succeed.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SpecValueBackfillService {

    private final PartRepository partRepository;
    private final PartSpecValueService partSpecValueService;

    public record Options(boolean dryRun, int limit) {}

    /** One part's outcome, for the per-part CSV. */
    public record Row(Long partId, String partNumber, String organisation,
                      int scalars, int ranges, int texts, int definitions) {}

    /** A value that should have parsed and did not, with how often it occurs. */
    public record Failure(String jsonName, String value, int count) {}

    public record Report(boolean dryRun, int parts, int scalars, int ranges, int texts,
                         int definitionsCreated, List<Failure> failures, List<Row> rows) {

        public int values() {
            return scalars + ranges + texts;
        }
    }

    public Report run(Options options) {
        // Identity only, and each part is then synced in its own transaction. Holding whole entities
        // across the loop would both waste memory and hand it a detached graph: there is no request
        // here, so no open-session-in-view, and a lazy organisation would throw on first read.
        List<Object[]> candidates = partRepository.findAllForSpecBackfill();
        if (options.limit() > 0 && candidates.size() > options.limit()) {
            candidates = candidates.subList(0, options.limit());
        }

        int scalars = 0, ranges = 0, texts = 0, definitions = 0;
        Map<String, Integer> failureCounts = new LinkedHashMap<>();
        List<Row> rows = new ArrayList<>();

        int n = 0;
        for (Object[] candidate : candidates) {
            Long partId = (Long) candidate[0];
            String partNumber = (String) candidate[1];
            String organisation = (String) candidate[2];

            PartSpecValueService.SyncResult r = options.dryRun()
                    ? partSpecValueService.previewById(partId)
                    : partSpecValueService.syncById(partId);

            scalars += r.scalars();
            ranges += r.ranges();
            texts += r.texts();
            definitions += r.definitionsCreated();
            r.unparsed().forEach(f -> failureCounts.merge(f, 1, Integer::sum));

            rows.add(new Row(partId, partNumber, organisation,
                    r.scalars(), r.ranges(), r.texts(), r.definitionsCreated()));

            if (++n % 250 == 0) {
                log.info("  {} / {} parts", n, candidates.size());
            }
        }
        int parts = candidates.size();

        List<Failure> failures = failureCounts.entrySet().stream()
                .map(e -> {
                    String[] kv = e.getKey().split("=", 2);
                    return new Failure(kv[0], kv.length > 1 ? kv[1] : "", e.getValue());
                })
                .sorted(Comparator.comparingInt(Failure::count).reversed()
                        .thenComparing(Failure::jsonName))
                .toList();

        return new Report(options.dryRun(), parts, scalars, ranges, texts, definitions,
                failures, rows);
    }
}
