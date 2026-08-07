package com.clele.parts.service;

import com.clele.parts.model.Part;
import com.clele.parts.repository.PartRepository;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds a replacement datasheet URL for parts whose stored one is dead.
 *
 * <p>Roughly a third of {@code part.datasheet_url} values arrived with the Partsbox import as
 * Octopart <em>tracking</em> links ({@code https://octopart.com/<id>/c1?t=<token>}) rather than
 * links to a file. The tokens have expired and the host now sits behind a bot wall, so they 403 for
 * everyone — they cannot be repaired, only replaced. This searches for the part's datasheet afresh
 * and rewrites {@code datasheet_url} when it finds one that survives verification.
 *
 * <p><b>Two gates, because a wrong datasheet is worse than none.</b> A web search for a part number
 * readily returns a PDF for a different part, and that would quietly poison any spec extraction
 * built on top of this. So a candidate is accepted only if (1) it parses as a PDF, and (2) its text
 * actually mentions the part number. The second gate cannot be applied to a scanned datasheet — no
 * text to match — so those are accepted but recorded separately as {@code RESOURCED_UNVERIFIED},
 * and are worth a human glance before they are trusted.
 */
@Slf4j
@Service
public class DatasheetResourcingService {

    /** Candidates examined per part before giving up — each costs a full PDF download. */
    private static final int MAX_CANDIDATES_PER_PART = 4;

    /**
     * Shortest prefix of a part number accepted as a mention. Below this the match stops meaning
     * anything: three characters of "SN7" would match every 74-series datasheet ever written.
     */
    private static final int MIN_MENTION_PREFIX = 5;

    private final PartRepository partRepository;
    private final DuckDuckGoDatasheetService searchService;
    private final DatasheetAnalyzer analyzer;
    private final RestTemplate restTemplate;

    /** Explicit constructor — see the note in {@link DatasheetBackfillService} about {@code @Qualifier}. */
    public DatasheetResourcingService(PartRepository partRepository,
                                      DuckDuckGoDatasheetService searchService,
                                      DatasheetAnalyzer analyzer,
                                      @Qualifier("datasheetRestTemplate") RestTemplate restTemplate) {
        this.partRepository = partRepository;
        this.searchService = searchService;
        this.analyzer = analyzer;
        this.restTemplate = restTemplate;
    }

    @Builder
    public record Row(
            Long partId,
            String partNumber,
            /** The URL being replaced — without it the run is not reversible from its own report. */
            String previousUrl,
            String query,
            String outcome,
            int candidatesFound,
            int candidatesTried,
            String chosenUrl,
            String route,
            String matchedOn,
            String rejections,
            boolean applied) {}

    @Builder
    public record Report(List<Row> rows, Map<String, Integer> byOutcome, int candidates, boolean dryRun) {}

    public record Options(boolean dryRun, int limit, long delayMillis) {}

    public Report run(Options options) {
        List<Part> candidates = partRepository.findWithDeadOctopartDatasheetUrl();
        log.info("{} part(s) carry a dead Octopart tracking URL", candidates.size());

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
            Row row = resourceOne(part, options.dryRun());
            rows.add(row);
            log.info("[{}/{}] {} -> {}{}", n, todo.size(), part.getPartNumber(), row.outcome(),
                    row.chosenUrl() != null ? " " + row.chosenUrl() : "");

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

    private Row resourceOne(Part part, boolean dryRun) {
        String query = buildQuery(part);
        Row.RowBuilder row = Row.builder()
                .partId(part.getId())
                .partNumber(part.getPartNumber())
                .previousUrl(part.getDatasheetUrl())
                .query(query);

        // The manufacturer's own URL first: it is a direct guess needing no search engine, and it
        // yields the real datasheet rather than a broker's re-host. Search is the fallback, and in
        // bulk it is usually unavailable (see DuckDuckGoDatasheetService).
        List<String> urls = new ArrayList<>(VendorDatasheetUrls.candidatesFor(part));
        int vendorCandidates = urls.size();

        if (urls.isEmpty()) {
            DuckDuckGoDatasheetService.SearchResult found;
            try {
                found = searchService.search(query);
            } catch (Exception e) {
                return row.outcome("SEARCH_FAILED").rejections(shorten(e.toString())).build();
            }
            // A blocked search is not an absent datasheet — reporting it as NO_CANDIDATES would say
            // "nothing exists for this part" about a request the search engine never answered.
            if (found.blocked()) {
                return row.outcome("SEARCH_BLOCKED").rejections(shorten(found.detail())).build();
            }
            if (found.status() == DuckDuckGoDatasheetService.SearchStatus.FAILED) {
                return row.outcome("SEARCH_FAILED").rejections(shorten(found.detail())).build();
            }
            found.results().forEach(s -> urls.add(s.getUrl()));
        }

        row.candidatesFound(urls.size());
        if (urls.isEmpty()) {
            return row.outcome("NO_CANDIDATES").build();
        }

        List<String> rejections = new ArrayList<>();
        int tried = 0;
        for (String url : urls) {
            if (tried >= Math.max(MAX_CANDIDATES_PER_PART, vendorCandidates)) {
                break;
            }
            tried++;

            byte[] data;
            try {
                data = download(url);
            } catch (Exception e) {
                rejections.add(stem(url) + ": " + shortHttp(e));
                continue;
            }

            DatasheetAnalyzer.Analysis analysis = analyzer.analyze(data);
            if (!analysis.usable()) {
                rejections.add(stem(url) + ": " + analysis.error());
                continue;
            }

            // Gate 2: the text must name the part. A scan has no text, so it cannot be checked.
            String matchedOn = null;
            boolean verified = false;
            if (analysis.route() == DatasheetAnalyzer.Route.NO_TEXT_LAYER) {
                matchedOn = "(scanned — unverifiable)";
            } else {
                matchedOn = mentionOf(part.getPartNumber(), textOf(data));
                if (matchedOn == null && part.getMpn() != null) {
                    matchedOn = mentionOf(part.getMpn(), textOf(data));
                }
                if (matchedOn == null) {
                    rejections.add(stem(url) + ": does not mention the part");
                    continue;
                }
                verified = true;
            }

            if (!dryRun) {
                part.setDatasheetUrl(url);
                partRepository.save(part);
            }
            return row.outcome(verified ? "RESOURCED" : "RESOURCED_UNVERIFIED")
                    .candidatesTried(tried)
                    .chosenUrl(url)
                    .route(analysis.route().name())
                    .matchedOn(matchedOn)
                    .rejections(String.join(" | ", rejections))
                    .applied(!dryRun)
                    .build();
        }

        return row.outcome("NO_MATCH")
                .candidatesTried(tried)
                .rejections(String.join(" | ", rejections))
                .build();
    }

    /**
     * Manufacturer plus MPN where both are known — a bare "SN7474N" pulls in every broker page on
     * the web, while "Texas Instruments SN7474N" tends to surface the real document. The search
     * service appends "datasheet filetype:pdf" itself.
     */
    private static String buildQuery(Part part) {
        String number = (part.getMpn() != null && !part.getMpn().isBlank())
                ? part.getMpn() : part.getPartNumber();
        String manufacturer = part.getManufacturer();
        return (manufacturer == null || manufacturer.isBlank()) ? number : manufacturer + " " + number;
    }

    /**
     * Whether the datasheet text names this part, returning the form that matched. Tries the whole
     * part number first, then drops trailing <em>letters</em> — real datasheets cover a family and
     * print "SN74LS30" where the stock record says "SN74LS30N", and imported numbers carry stray
     * package suffixes ("MC1489P."). Comparison ignores case and punctuation, so "MC14-89P" in a
     * table still matches.
     *
     * <p><b>Digits are never trimmed, and that restriction is the whole point.</b> An earlier version
     * shortened by one character regardless of kind, which silently attached TI's SN7416 hex-inverter
     * datasheet to SN74163, SN74164, SN74165 and SN74161 — four unrelated counters and shift
     * registers — because "SN7416" is a prefix of "SN74163" and appears 35 times in that document.
     * A trailing letter is a package or revision code and drops harmlessly; a trailing digit is part
     * of the part's identity, and removing it turns the check into a family-prefix match that
     * confidently accepts the wrong document. The cost of the stricter rule is the occasional false
     * negative (a suffix ending in a digit, like "SN7402NE4", no longer reduces to "SN7402"), which
     * surfaces as an honest NO_MATCH rather than as corrupt data.
     */
    static String mentionOf(String partNumber, String text) {
        if (partNumber == null || text == null) {
            return null;
        }
        String needle = normalise(partNumber);
        if (needle.length() < MIN_MENTION_PREFIX || text.isBlank()) {
            return null;
        }
        List<String> tokens = tokenise(text);
        for (int len = needle.length(); len >= MIN_MENTION_PREFIX; len--) {
            String prefix = needle.substring(0, len);
            for (String token : tokens) {
                if (isMention(token, prefix)) {
                    return prefix;
                }
            }
            // Stop before removing a digit: everything shorter would be a different part.
            if (Character.isDigit(needle.charAt(len - 1))) {
                return null;
            }
        }
        return null;
    }

    /**
     * Whether one word of the datasheet names the part. The token may carry a longer package suffix
     * than the stock record ("SN74LS30N" where we asked for "SN74LS30"), but a digit immediately
     * after the match means it is a <em>different</em> part in the same family — "SN74163" is not a
     * mention of "SN7416".
     */
    private static boolean isMention(String token, String prefix) {
        if (!token.startsWith(prefix)) {
            return false;
        }
        return token.length() == prefix.length() || !Character.isDigit(token.charAt(prefix.length()));
    }

    /**
     * The datasheet split into words, each stripped of punctuation.
     *
     * <p>Splitting on whitespace <em>first</em> is load-bearing. An earlier version normalised the
     * whole document into one punctuation-free string and searched that, which let adjacent words
     * fuse across line breaks and invent part numbers the document never contained: TI's SN7417
     * datasheet ends a line with "SN7417" and starts the next with "4", so the flattened text
     * contained "SN74174" and the hex-buffer datasheet was confidently attached to the SN74174 hex
     * flip-flop. The same accident matched SN74161 in the SN7416 datasheet. Punctuation is still
     * dropped inside a word, so a table printing "MC14-89P" matches "MC1489P".
     */
    private static List<String> tokenise(String text) {
        List<String> tokens = new ArrayList<>();
        for (String raw : text.split("\\s+")) {
            String t = normalise(raw);
            if (!t.isEmpty()) {
                tokens.add(t);
            }
        }
        return tokens;
    }

    private static String normalise(String s) {
        return s.toUpperCase().replaceAll("[^A-Z0-9]", "");
    }

    private String textOf(byte[] data) {
        try (PDDocument doc = Loader.loadPDF(data)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return stripper.getText(doc);
        } catch (Exception e) {
            return "";
        }
    }

    private byte[] download(String url) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("User-Agent",
                "Mozilla/5.0 (X11; Linux x86_64; rv:124.0) Gecko/20100101 Firefox/124.0");
        headers.set("Accept", "application/pdf,*/*");
        ResponseEntity<byte[]> response =
                restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), byte[].class);
        byte[] body = response.getBody();
        return body == null ? new byte[0] : body;
    }

    /** Short label for a candidate in the rejection trail — the filename says which stem was tried. */
    private static String stem(String url) {
        try {
            String path = java.net.URI.create(url).getPath();
            String name = path.substring(path.lastIndexOf('/') + 1);
            return name.isBlank() ? url : name;
        } catch (Exception e) {
            return url;
        }
    }

    /** "404 NOT_FOUND" rather than the vendor's whole HTML error page. */
    private static String shortHttp(Exception e) {
        if (e instanceof org.springframework.web.client.HttpStatusCodeException h) {
            return h.getStatusCode().toString();
        }
        return shorten(e.toString());
    }

    private static String shorten(String s) {
        if (s == null) return null;
        String one = s.replaceAll("\\s+", " ").trim();
        return one.length() <= 160 ? one : one.substring(0, 160) + "…";
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
