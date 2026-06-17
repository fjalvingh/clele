package com.clele.parts.imports;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * Command-line entry point for the Partsbox import. Active only under the {@code import}
 * Spring profile, so a normal application start is unaffected. Run with:
 *
 * <pre>
 * mvn21 spring-boot:run -Dspring-boot.run.profiles=import \
 *   -Dspring-boot.run.arguments=--partsbox.file=../data.txt
 * </pre>
 *
 * The {@code import} profile disables the web server (see application-import.yml), so the
 * process imports and exits.
 */
@Component
@Profile("import")
@RequiredArgsConstructor
public class PartsboxImportRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PartsboxImportRunner.class);
    private static final String DEFAULT_FILE = "data.txt";

    private final PartsboxImportService importService;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        String file = DEFAULT_FILE;
        if (args.containsOption("partsbox.file") && !args.getOptionValues("partsbox.file").isEmpty()) {
            file = args.getOptionValues("partsbox.file").get(0);
        }
        log.info("Starting Partsbox import from '{}'", file);
        PartsboxImportService.ImportSummary summary = importService.importFile(Path.of(file));
        log.info("Imported {} parts, {} stock movements, {} stock entries ({} merged names, {} zero-stock parts); "
                        + "images: {} downloaded, {} failed",
                summary.parts(), summary.movements(), summary.stockEntries(),
                summary.mergedNames(), summary.zeroStockParts(),
                summary.imagesDownloaded(), summary.imagesFailed());
    }
}
