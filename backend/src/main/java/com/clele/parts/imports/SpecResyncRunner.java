package com.clele.parts.imports;

import com.clele.parts.service.SpecResyncService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Command-line entry point for the spec-value re-sync. Active only under the {@code specs} Spring
 * profile, so a normal application start is unaffected.
 *
 * <p>Dry run — reads everything, writes nothing, and prints what a commit would drop:
 *
 * <pre>
 * mvn21 spring-boot:run -Dspring-boot.run.profiles=specs -DskipFrontend=true
 * </pre>
 *
 * <p>Commit:
 *
 * <pre>
 * mvn21 spring-boot:run -Dspring-boot.run.profiles=specs -DskipFrontend=true \
 *   -Dspring-boot.run.arguments=--specs.dry-run=false
 * </pre>
 *
 * <p>The profile sets {@code web-application-type: none} (see {@code application-specs.yml}), so the
 * process exits when the job is done rather than staying up as a web server.
 *
 * <p>Options: {@code --specs.dry-run} (default {@code true}), {@code --specs.limit} (0 = every
 * part). <b>Read the dropped-value list before committing</b> — a NUMBER definition that should
 * have been TEXT loses its values here.
 */
@Component
@Profile("specs")
@RequiredArgsConstructor
public class SpecResyncRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SpecResyncRunner.class);

    private final SpecResyncService resyncService;

    @Override
    public void run(ApplicationArguments args) {
        boolean dryRun = boolArg(args, "specs.dry-run", true);
        int limit = intArg(args, "specs.limit", 0);

        log.info("spec-resync starting ({}{})", dryRun ? "dry run" : "COMMITTING",
                limit > 0 ? ", limit " + limit : "");
        SpecResyncService.Report report = resyncService.run(!dryRun, limit);

        log.info("spec-resync {}: {} parts, {} scalars, {} ranges, {} text values, {} dropped",
                dryRun ? "dry run" : "committed", report.parts(), report.scalars(), report.ranges(),
                report.texts(), report.refused());

        if (report.dropped().isEmpty()) {
            log.info("spec-resync nothing would be dropped");
            return;
        }
        log.warn("spec-resync {} value(s) across {} spec field(s) cannot be read as numbers and "
                        + "{} dropped — check whether the field should be TEXT instead:",
                report.refused(), report.dropped().size(), dryRun ? "would be" : "were");
        report.dropped().forEach((key, count) -> {
            List<String> samples = report.samples().getOrDefault(key, List.of());
            log.warn("  {} × {}   e.g. {}", count, key, String.join(" | ", samples));
        });
    }

    private static boolean boolArg(ApplicationArguments args, String name, boolean fallback) {
        List<String> values = args.getOptionValues(name);
        return values == null || values.isEmpty() ? fallback : Boolean.parseBoolean(values.get(0));
    }

    private static int intArg(ApplicationArguments args, String name, int fallback) {
        List<String> values = args.getOptionValues(name);
        if (values == null || values.isEmpty()) return fallback;
        try {
            return Integer.parseInt(values.get(0).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
