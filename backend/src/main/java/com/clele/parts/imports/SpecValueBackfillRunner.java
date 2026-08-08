package com.clele.parts.imports;

import com.clele.parts.service.SpecValueBackfillService;
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

/**
 * Command-line entry point for the spec-value backfill — step 3 of the typed spec value migration.
 * Active only under the {@code specvalues} Spring profile, so a normal application start is
 * unaffected.
 *
 * <p>Preview (the default — classifies everything, writes nothing):
 *
 * <pre>
 * mvn21 spring-boot:run -Dspring-boot.run.profiles=specvalues
 * </pre>
 *
 * <p>Backfill:
 *
 * <pre>
 * mvn21 spring-boot:run -Dspring-boot.run.profiles=specvalues \
 *   -Dspring-boot.run.arguments=--specvalues.dry-run=false
 * </pre>
 *
 * <p>Options: {@code --specvalues.dry-run} (default {@code true}), {@code --specvalues.limit}
 * (0 = all), {@code --specvalues.report} (CSV path, default {@code spec-value-report.csv}).
 *
 * <p>Safe to re-run: the sync is idempotent and touches no network, so a second pass converges on
 * the same rows.
 */
@Component
@Profile("specvalues")
@RequiredArgsConstructor
public class SpecValueBackfillRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SpecValueBackfillRunner.class);

    private final SpecValueBackfillService backfillService;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        boolean dryRun = boolArg(args, "specvalues.dry-run", true);
        int limit = intArg(args, "specvalues.limit", 0);
        String reportPath = stringArg(args, "specvalues.report", "spec-value-report.csv");

        log.info("Spec value {} starting (limit={})",
                dryRun ? "PREVIEW (dry run, nothing will be written)" : "BACKFILL (will write rows)",
                limit == 0 ? "all" : limit);

        SpecValueBackfillService.Report report =
                backfillService.run(new SpecValueBackfillService.Options(dryRun, limit));

        writeCsv(report, Path.of(reportPath));
        logSummary(report, reportPath);
    }

    private void logSummary(SpecValueBackfillService.Report report, String reportPath) {
        int values = report.values();
        log.info("");
        log.info("================= SPEC VALUE {} =================",
                report.dryRun() ? "PREVIEW " : "BACKFILL");
        log.info("Parts processed        : {}", report.parts());
        log.info("Spec values classified : {}", values);
        log.info("");
        log.info("  parsed scalars       : {}  ({}%)", report.scalars(), percent(report.scalars(), values));
        log.info("  parsed ranges        : {}  ({}%)", report.ranges(), percent(report.ranges(), values));
        log.info("  kept as text         : {}  ({}%)", report.texts(), percent(report.texts(), values));
        log.info("");
        int typed = report.scalars() + report.ranges();
        log.info("Numerically queryable  : {} of {} ({}%)", typed, values, percent(typed, values));
        log.info("Spec definitions {}: {}",
                report.dryRun() ? "that would be created " : "created               ",
                report.definitionsCreated());

        if (report.failures().isEmpty()) {
            log.info("");
            log.info("No unparseable values in any field that declares a unit family.");
        } else {
            int total = report.failures().stream().mapToInt(SpecValueBackfillService.Failure::count).sum();
            log.info("");
            log.info("{} value(s) in {} distinct form(s) declare a unit family but did not parse.",
                    total, report.failures().size());
            log.info("These stay as text — nothing was extracted, so nothing is wrong; they are");
            log.info("simply not searchable as numbers until corrected by hand:");
            report.failures().stream().limit(40).forEach(f ->
                    log.info(String.format("  %5d x  %-34s %s", f.count(), f.jsonName(), f.value())));
            if (report.failures().size() > 40) {
                log.info("  ... and {} more (see {})", report.failures().size() - 40, reportPath);
            }
        }

        log.info("");
        log.info("Per-part detail written to {}", reportPath);
        if (report.dryRun()) {
            log.info("Dry run — no rows written. Re-run with --specvalues.dry-run=false to apply.");
        }
        log.info("=========================================================");
    }

    private void writeCsv(SpecValueBackfillService.Report report, Path path) throws IOException {
        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(path, StandardCharsets.UTF_8))) {
            out.println("part_id,part_number,organisation,scalars,ranges,texts,definitions_created");
            for (SpecValueBackfillService.Row r : report.rows()) {
                out.printf("%d,%s,%s,%d,%d,%d,%d%n", r.partId(), csv(r.partNumber()),
                        csv(r.organisation()), r.scalars(), r.ranges(), r.texts(), r.definitions());
            }
            out.println();
            out.println("json_name,value,count");
            for (SpecValueBackfillService.Failure f : report.failures()) {
                out.printf("%s,%s,%d%n", csv(f.jsonName()), csv(f.value()), f.count());
            }
        }
        log.info("Wrote {} part row(s) and {} failure row(s) to {}",
                report.rows().size(), report.failures().size(), path.toAbsolutePath());
    }

    private static int percent(int part, int total) {
        return total == 0 ? 0 : (int) Math.round(100.0 * part / total);
    }

    private static String csv(String s) {
        if (s == null) return "";
        String v = s.replace("\"", "\"\"");
        return v.contains(",") || v.contains("\"") || v.contains("\n") ? "\"" + v + "\"" : v;
    }

    private static boolean boolArg(ApplicationArguments args, String name, boolean def) {
        var values = args.getOptionValues(name);
        return (values == null || values.isEmpty()) ? def : Boolean.parseBoolean(values.get(0));
    }

    private static int intArg(ApplicationArguments args, String name, int def) {
        var values = args.getOptionValues(name);
        if (values == null || values.isEmpty()) return def;
        try {
            return Integer.parseInt(values.get(0));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static String stringArg(ApplicationArguments args, String name, String def) {
        var values = args.getOptionValues(name);
        return (values == null || values.isEmpty()) ? def : values.get(0);
    }
}
