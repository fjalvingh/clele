package com.clele.parts.imports;

import com.clele.parts.service.DatasheetBackfillService;
import com.clele.parts.service.DatasheetResourcingService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Command-line entry point for the datasheet preflight and backfill. Active only under the
 * {@code datasheets} Spring profile, so a normal application start is unaffected.
 *
 * <p>Preflight (the default — downloads and classifies, writes nothing to the database):
 *
 * <pre>
 * mvn21 spring-boot:run -Dspring-boot.run.profiles=datasheets \
 *   -Dspring-boot.run.arguments=--datasheets.limit=50
 * </pre>
 *
 * <p>Backfill (same pass, but also stores each PDF as a {@code DATASHEET} attachment):
 *
 * <pre>
 * mvn21 spring-boot:run -Dspring-boot.run.profiles=datasheets \
 *   -Dspring-boot.run.arguments=--datasheets.dry-run=false
 * </pre>
 *
 * <p>Re-sourcing (a different job: finds replacement URLs for parts whose stored Octopart tracking
 * link is dead, and rewrites {@code part.datasheet_url}):
 *
 * <pre>
 * mvn21 spring-boot:run -Dspring-boot.run.profiles=datasheets \
 *   -Dspring-boot.run.arguments="--datasheets.resource=true --datasheets.dry-run=false"
 * </pre>
 *
 * <p>Options: {@code --datasheets.dry-run} (default {@code true}), {@code --datasheets.limit}
 * (0 = all), {@code --datasheets.resource} (default {@code false}), {@code --datasheets.delay-ms}
 * (default 250, or 3000 when re-sourcing — that path scrapes a search engine per part),
 * {@code --datasheets.report} (CSV path).
 */
@Component
@Profile("datasheets")
@RequiredArgsConstructor
public class DatasheetBackfillRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DatasheetBackfillRunner.class);

    private final DatasheetBackfillService backfillService;
    private final DatasheetResourcingService resourcingService;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        boolean dryRun = boolArg(args, "datasheets.dry-run", true);
        int limit = intArg(args, "datasheets.limit", 0);
        boolean resource = boolArg(args, "datasheets.resource", false);
        String reportPath = stringArg(args, "datasheets.report",
                resource ? "datasheet-resourcing.csv" : "datasheet-report.csv");
        // Re-sourcing scrapes a search engine once per part, so it needs a far more patient default
        // than the backfill, which only fetches files from vendor CDNs.
        long delay = intArg(args, "datasheets.delay-ms", resource ? 3_000 : 250);

        if (resource) {
            log.info("Datasheet RE-SOURCING starting{} (limit={}, delay={}ms)",
                    dryRun ? " (dry run, datasheet_url will not be updated)" : "",
                    limit == 0 ? "all" : limit, delay);
            DatasheetResourcingService.Report report =
                    resourcingService.run(new DatasheetResourcingService.Options(dryRun, limit, delay));
            writeResourcingCsv(report, Path.of(reportPath));
            logResourcingSummary(report, reportPath);
            return;
        }

        log.info("Datasheet {} starting (limit={}, delay={}ms)",
                dryRun ? "PREFLIGHT (dry run, nothing will be stored)" : "BACKFILL (will store PDFs)",
                limit == 0 ? "all" : limit, delay);

        DatasheetBackfillService.Report report =
                backfillService.run(new DatasheetBackfillService.Options(dryRun, limit, delay));

        writeCsv(report, Path.of(reportPath));
        logSummary(report, reportPath);
    }

    private void logResourcingSummary(DatasheetResourcingService.Report report, String reportPath) {
        int processed = report.rows().size();
        log.info("");
        log.info("================= DATASHEET RE-SOURCING =================");
        log.info("Parts with a dead Octopart tracking URL : {}", report.candidates());
        log.info("Processed this run                      : {}", processed);
        log.info("");
        log.info("Outcome breakdown:");
        for (Map.Entry<String, Integer> e : report.byOutcome().entrySet()) {
            log.info(String.format("  %-22s %5d  (%d%%)",
                    e.getKey(), e.getValue(), percent(e.getValue(), processed)));
        }
        int fixed = report.byOutcome().getOrDefault("RESOURCED", 0)
                + report.byOutcome().getOrDefault("RESOURCED_UNVERIFIED", 0);
        log.info("");
        log.info("Replaced {} of {} ({}%)", fixed, processed, percent(fixed, processed));
        log.info("Per-part detail written to {}", reportPath);
        if (report.dryRun()) {
            log.info("Dry run — datasheet_url unchanged. Re-run with --datasheets.dry-run=false to apply.");
        }
        log.info("=========================================================");
    }

    private void writeResourcingCsv(DatasheetResourcingService.Report report, Path path) throws IOException {
        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(path, StandardCharsets.UTF_8))) {
            out.println("part_id,part_number,outcome,query,candidates_found,candidates_tried,"
                    + "route,matched_on,applied,chosen_url,previous_url,rejections");
            for (DatasheetResourcingService.Row r : report.rows()) {
                out.printf("%d,%s,%s,%s,%d,%d,%s,%s,%s,%s,%s,%s%n",
                        r.partId(), csv(r.partNumber()), r.outcome(), csv(r.query()),
                        r.candidatesFound(), r.candidatesTried(), csv(r.route()), csv(r.matchedOn()),
                        r.applied(), csv(r.chosenUrl()), csv(r.previousUrl()), csv(r.rejections()));
            }
        }
        log.info("Wrote {} row(s) to {}", report.rows().size(), path.toAbsolutePath());
    }

    private void logSummary(DatasheetBackfillService.Report report, String reportPath) {
        int processed = report.rows().size();
        log.info("");
        log.info("==================== DATASHEET {} ====================",
                report.dryRun() ? "PREFLIGHT" : "BACKFILL");
        log.info("Parts with an undownloaded datasheet URL : {}", report.candidates());
        log.info("Processed this run                       : {}", processed);
        log.info("");
        log.info("Outcome breakdown:");
        for (Map.Entry<String, Integer> e : report.byOutcome().entrySet()) {
            log.info(String.format("  %-16s %5d  (%d%%)",
                    e.getKey(), e.getValue(), percent(e.getValue(), processed)));
        }
        log.info("");

        int text = report.byOutcome().getOrDefault("TEXT", 0);
        int imageTables = report.byOutcome().getOrDefault("IMAGE_TABLES", 0);
        int noText = report.byOutcome().getOrDefault("NO_TEXT_LAYER", 0);
        int vision = imageTables + noText;
        int usable = text + vision;

        log.info("Routing for spec extraction:");
        log.info("  text extraction  : {} ({}% of usable)", text, percent(text, usable));
        log.info("  vision required  : {} ({}% of usable)  [{} tables-as-images, {} full scans]",
                vision, percent(vision, usable), imageTables, noText);
        log.info("");
        log.info("Per-part detail written to {}", reportPath);
        if (report.dryRun()) {
            log.info("Dry run — nothing stored. Re-run with --datasheets.dry-run=false to keep the PDFs.");
        }
        log.info("=========================================================");
    }

    private void writeCsv(DatasheetBackfillService.Report report, Path path) throws IOException {
        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(path, StandardCharsets.UTF_8))) {
            out.println("part_id,part_number,outcome,http_status,bytes,pages,text_chars,"
                    + "heading_hits,headings,stored,error,url");
            for (DatasheetBackfillService.Row r : report.rows()) {
                out.printf("%d,%s,%s,%d,%d,%d,%d,%d,%s,%s,%s,%s%n",
                        r.partId(),
                        csv(r.partNumber()),
                        r.outcome(),
                        r.httpStatus(),
                        r.bytes(),
                        r.pages(),
                        r.textChars(),
                        r.headingHits(),
                        csv(String.join("; ", r.headings())),
                        r.stored(),
                        csv(r.error()),
                        csv(r.url()));
            }
        }
        log.info("Wrote {} row(s) to {}", report.rows().size(), path.toAbsolutePath());
    }

    /** Minimal CSV quoting: wrap in quotes and double any embedded quote. */
    private static String csv(String value) {
        if (value == null) {
            return "";
        }
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private static int percent(int n, int total) {
        return total == 0 ? 0 : Math.round(n * 100f / total);
    }

    private static boolean boolArg(ApplicationArguments args, String name, boolean fallback) {
        String v = stringArg(args, name, null);
        return v == null ? fallback : Boolean.parseBoolean(v);
    }

    private static int intArg(ApplicationArguments args, String name, int fallback) {
        String v = stringArg(args, name, null);
        if (v == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            log.warn("Ignoring non-numeric --{}={}", name, v);
            return fallback;
        }
    }

    private static String stringArg(ApplicationArguments args, String name, String fallback) {
        if (args.containsOption(name) && !args.getOptionValues(name).isEmpty()) {
            return args.getOptionValues(name).get(0);
        }
        return fallback;
    }
}
