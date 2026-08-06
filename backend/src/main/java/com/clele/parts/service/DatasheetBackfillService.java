package com.clele.parts.service;

import com.clele.parts.model.AttachmentType;
import com.clele.parts.model.Part;
import com.clele.parts.model.PartAttachment;
import com.clele.parts.repository.PartAttachmentRepository;
import com.clele.parts.repository.PartRepository;
import com.clele.parts.util.UrlSafety;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Downloads the datasheet PDFs that {@code part.datasheet_url} points at and reports what is
 * actually in them, so the cost and feasibility of spec extraction can be judged before any of it
 * is built.
 *
 * <p>Runs in one pass with a {@code dryRun} switch rather than as a separate probe and fetch: the
 * only way to tell a usable datasheet from a scan is to parse it, so a "preflight" that did not
 * download would have nothing to classify. With {@code dryRun} the bytes are analysed and
 * discarded; without it the same bytes are also stored as a {@code DATASHEET} attachment.
 *
 * <p>Each part is committed in its own transaction, so a failure part-way through a long run
 * leaves everything already fetched in place — re-running skips parts that now have an
 * attachment (see {@code PartRepository.findWithUndownloadedDatasheet}) and continues.
 */
@Slf4j
@Service
public class DatasheetBackfillService {

    /**
     * Largest response that will be stored, comfortably above the biggest MCU datasheets (~30 MB).
     * Note this is checked <em>after</em> the body is read: {@code RestTemplate} buffers the whole
     * response into a {@code byte[]} either way, so this bounds what lands in the database, not
     * peak heap.
     */
    private static final int MAX_BYTES = 40 * 1024 * 1024;

    private final PartRepository partRepository;
    private final PartAttachmentRepository partAttachmentRepository;
    private final DatasheetAnalyzer analyzer;
    private final RestTemplate restTemplate;

    /**
     * Constructor written out rather than generated: there are three {@code RestTemplate} beans and
     * this one needs the long-timeout {@code datasheetRestTemplate}. Lombok does not copy
     * {@code @Qualifier} onto generated constructor parameters unless {@code lombok.config} says
     * so, and there is no {@code lombok.config} here — with {@code @RequiredArgsConstructor} the
     * annotation would be dropped and Spring would fall back to matching the parameter name,
     * silently injecting the 30-second {@code restTemplate} instead.
     */
    public DatasheetBackfillService(PartRepository partRepository,
                                    PartAttachmentRepository partAttachmentRepository,
                                    DatasheetAnalyzer analyzer,
                                    @Qualifier("datasheetRestTemplate") RestTemplate restTemplate) {
        this.partRepository = partRepository;
        this.partAttachmentRepository = partAttachmentRepository;
        this.analyzer = analyzer;
        this.restTemplate = restTemplate;
    }

    /** What happened to one part. */
    @Builder
    public record Row(
            Long partId,
            String partNumber,
            String url,
            String outcome,
            int httpStatus,
            int bytes,
            int pages,
            int textChars,
            int headingHits,
            Set<String> headings,
            boolean stored,
            String error) {}

    @Builder
    public record Report(List<Row> rows, Map<String, Integer> byOutcome, int candidates, boolean dryRun) {}

    public record Options(boolean dryRun, int limit, long delayMillis) {}

    public Report run(Options options) {
        List<Part> candidates = partRepository.findWithUndownloadedDatasheet();
        log.info("{} part(s) carry a datasheet URL with nothing downloaded yet", candidates.size());

        List<Part> todo = (options.limit() > 0 && options.limit() < candidates.size())
                ? candidates.subList(0, options.limit())
                : candidates;
        if (todo.size() != candidates.size()) {
            log.info("Limited to the first {} by --datasheets.limit", todo.size());
        }

        List<Row> rows = new ArrayList<>(todo.size());
        int n = 0;
        for (Part part : todo) {
            n++;
            Row row = process(part, options.dryRun());
            rows.add(row);
            log.info("[{}/{}] {} -> {}{}", n, todo.size(), part.getPartNumber(), row.outcome(),
                    row.error() != null ? " (" + row.error() + ")" : "");

            if (options.delayMillis() > 0 && n < todo.size()) {
                sleep(options.delayMillis());
            }
        }

        Map<String, Integer> byOutcome = new LinkedHashMap<>();
        for (Row row : rows) {
            byOutcome.merge(row.outcome(), 1, Integer::sum);
        }

        return Report.builder()
                .rows(rows)
                .byOutcome(byOutcome)
                .candidates(candidates.size())
                .dryRun(options.dryRun())
                .build();
    }

    private Row process(Part part, boolean dryRun) {
        Row.RowBuilder row = Row.builder()
                .partId(part.getId())
                .partNumber(part.getPartNumber())
                .url(part.getDatasheetUrl())
                .headings(Set.of());

        Fetched fetched;
        try {
            fetched = fetch(part.getDatasheetUrl());
        } catch (Exception e) {
            return row.outcome("DOWNLOAD_FAILED").error(rootMessage(e)).build();
        }
        row.httpStatus(fetched.status()).bytes(fetched.data().length);

        if (fetched.data().length > MAX_BYTES) {
            return row.outcome("OVERSIZE")
                    .error(fetched.data().length + " bytes exceeds the " + MAX_BYTES + " byte cap")
                    .build();
        }

        DatasheetAnalyzer.Analysis analysis = analyzer.analyze(fetched.data());
        row.pages(analysis.pages())
                .textChars(analysis.textChars())
                .headingHits(analysis.headingHits())
                .headings(analysis.headings());

        if (!analysis.usable()) {
            return row.outcome("UNUSABLE").error(analysis.error()).build();
        }

        boolean stored = false;
        if (!dryRun) {
            try {
                store(part, fetched);
                stored = true;
            } catch (Exception e) {
                return row.outcome(analysis.route().name()).error("store failed: " + rootMessage(e)).build();
            }
        }
        return row.outcome(analysis.route().name()).stored(stored).build();
    }

    /**
     * Persist one datasheet. Deliberately <em>not</em> annotated {@code @Transactional}: this is
     * called from {@link #process} on the same bean, and self-invocation does not pass through the
     * proxy, so the annotation would be silently inert. The run needs no outer transaction anyway —
     * {@code JpaRepository.save} is itself transactional, which makes each part its own commit and
     * is exactly the resumability boundary wanted here. {@code part} is detached (loaded outside a
     * transaction); Hibernate only needs its id to write the FK.
     */
    private void store(Part part, Fetched fetched) {
        PartAttachment attachment = PartAttachment.builder()
                .part(part)
                .type(AttachmentType.DATASHEET)
                .displayOrder(partAttachmentRepository.countByPartIdAndType(part.getId(), AttachmentType.DATASHEET))
                .data(fetched.data())
                .contentType(MediaType.APPLICATION_PDF_VALUE)
                .filename(filenameFor(part, fetched.url()))
                .build();
        partAttachmentRepository.save(attachment);
    }

    record Fetched(byte[] data, String contentType, int status, String url) {}

    /**
     * Fetch the URL with browser-ish headers — several vendor CDNs return 403 to a bare Java
     * user-agent. SSRF-guarded via {@link UrlSafety}, exactly as the interactive
     * "Download from URL" path is.
     */
    private Fetched fetch(String url) {
        UrlSafety.validateExternalHttpUrl(url);

        HttpHeaders headers = new HttpHeaders();
        headers.set("User-Agent",
                "Mozilla/5.0 (X11; Linux x86_64; rv:124.0) Gecko/20100101 Firefox/124.0");
        headers.set("Accept", "application/pdf,*/*");
        headers.set("Accept-Language", "en-US,en;q=0.5");

        ResponseEntity<byte[]> response =
                restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), byte[].class);

        byte[] body = response.getBody();
        if (body == null) {
            body = new byte[0];
        }
        MediaType ct = response.getHeaders().getContentType();
        return new Fetched(body, ct != null ? ct.toString() : null, response.getStatusCode().value(), url);
    }

    /** Prefer the URL's own filename; fall back to the part number. */
    private static String filenameFor(Part part, String url) {
        try {
            String path = java.net.URI.create(url).getPath();
            if (path != null) {
                String name = path.substring(path.lastIndexOf('/') + 1);
                if (!name.isBlank() && name.toLowerCase().endsWith(".pdf")) {
                    return name;
                }
            }
        } catch (Exception ignored) {
            // fall through
        }
        return part.getPartNumber().replaceAll("[^A-Za-z0-9._-]", "_") + ".pdf";
    }

    /**
     * A one-line failure message. Truncated hard: an HTTP error from a vendor site carries the whole
     * HTML body (Octopart's 403 page is 51 KB), which otherwise floods the log and the CSV.
     */
    private static String rootMessage(Exception e) {
        String m = e.getMessage();
        if (m == null || m.isBlank()) {
            return e.getClass().getSimpleName();
        }
        m = m.replaceAll("\\s+", " ").trim();
        return m.length() <= 160 ? m : m.substring(0, 160) + "…";
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
