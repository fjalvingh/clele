package com.clele.parts.service;

import com.clele.parts.dto.DatasheetExtractionDTO;
import com.clele.parts.dto.ExtractedSpecDTO;
import com.clele.parts.dto.PartSearchResultDTO;
import com.clele.parts.model.AttachmentType;
import com.clele.parts.model.Part;
import com.clele.parts.model.PartAttachment;
import com.clele.parts.repository.PartAttachmentRepository;
import com.clele.parts.util.PdfBytes;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;

/**
 * Reads a stored datasheet PDF and proposes specifications and a functional description for the
 * part it belongs to — the "Get specs from document" action on Part Detail.
 *
 * <p><b>Why this exists next to the web lookup.</b> {@link AiPartSearchService} searches the open
 * web, which is where most of its cost lives (measured: ~89% of a lookup is web searches plus the
 * results replayed through the tool loop). This path searches nothing: the document is already in
 * the database, so the only cost is the excerpt sent in and the answer sent back — cents into
 * tenths of a cent. It is also the licensing-clean source. A manufacturer datasheet is a published
 * document about one part, not a compiled parametric database, so nothing restricts retaining what
 * it says; see CLAUDE.md → <i>Part metadata sources</i> for why the distributor APIs are not an
 * option.
 *
 * <p><b>Nothing runs automatically.</b> Each call spends money and takes seconds, and most parts
 * that have a datasheet already have specs. It runs when the user presses the button, on the
 * document the user picked.
 *
 * <p><b>Nothing is written here either.</b> The result is a <em>proposal</em>: the caller confirms
 * it field by field and applies it through {@code POST /parts/{id}/ai-apply}, the same path the web
 * lookup uses. Extraction from a document is more accurate than a web search but not infallible —
 * an "Absolute Maximum Ratings" figure is not the recommended operating value, and a datasheet that
 * covers a family prints values for parts other than this one.
 *
 * <h2>What gets sent to the model</h2>
 *
 * Not the document. A datasheet runs to tens of pages of pinout drawings, package outlines,
 * ordering tables and revision history; sending all of it would cost more than the web lookup it is
 * meant to undercut. {@link #buildExcerpt} sends two things instead:
 *
 * <ol>
 *   <li>the <b>front matter</b> — page 1, and page 2 when page 1 is thin — which carries the title
 *       block, the feature list and the functional description; and</li>
 *   <li>a <b>window around every parametric section heading</b> {@link DatasheetAnalyzer} found
 *       ("Absolute Maximum Ratings", "DC Characteristics", …), which is where the values are.</li>
 * </ol>
 *
 * Windows that overlap are merged, and the whole excerpt is capped. Pages are marked in the text so
 * the model can report which page each value came from — a value you cannot trace is a value you
 * cannot defend when it later turns out to be wrong, and the confirmation UI shows the page beside
 * the value.
 *
 * <h2>Routing, and what it refuses</h2>
 *
 * {@link DatasheetAnalyzer} classifies the PDF first, and the route decides whether this path can
 * work at all:
 *
 * <ul>
 *   <li>{@code TEXT} — parametric headings are in the text layer. This is the case this service is
 *       for; ~64% of the usable datasheets in this catalogue.</li>
 *   <li>{@code IMAGE_TABLES} — a text layer exists but the parametric tables are pasted-in scans.
 *       It still runs, because the front matter is real text and usually yields a description, but
 *       the response says so rather than reporting a thin result as a complete one.</li>
 *   <li>{@code NO_TEXT_LAYER} — a pure scan. Refused outright: there is nothing to send, and
 *       spending a model call to be told so is waste. The vision path is not built.</li>
 * </ul>
 */
@Slf4j
@Service
@Transactional(readOnly = true)
public class DatasheetSpecExtractionService {

    private static final String API_URL = "https://api.anthropic.com/v1/messages";
    private static final String API_VERSION = "2023-06-01";

    /**
     * Excerpt budget. ~90k characters is roughly 22k tokens, well under a tenth of a cent of input
     * at Haiku rates and enough for the front matter plus every parametric section of a normal
     * datasheet. A document that overruns it is one where the later sections repeat the earlier
     * ones (per-grade tables, per-package variants), so truncating costs little.
     */
    private static final int MAX_EXCERPT_CHARS = 90_000;

    /** How much text to take from a parametric heading onwards. About two dense pages. */
    private static final int HEADING_WINDOW_CHARS = 6_000;

    /** Front matter: page 1 always, page 2 as well when page 1 is little more than a title. */
    private static final int FRONT_MATTER_PAGES = 2;
    private static final int THIN_PAGE_CHARS = 1_200;

    /**
     * The prompt in four blocks: what the reader is, what to return, how to key a spec, and how to
     * read a datasheet. Only the second differs between the two callers — {@link #extract}, which
     * is told the part and wants its data, and {@link #identify}, which has the document and
     * nothing else. The spec-key block and the reading rules are shared on purpose: a key described
     * one way here and another way there lands in {@code part.specs} as two fields, and "prefer
     * typical over absolute maximum" is not a rule that stops applying because the part is unknown.
     */
    private static final String READER_INTRO = """
            You are reading a manufacturer datasheet for a single electronic component and \
            extracting its data for an inventory system.
            """;

    private static final String EXTRACT_FIELDS = """
            Return ONLY a valid JSON object with no markdown formatting, no code blocks, no \
            explanation, with exactly these fields:
            - details: 2-5 sentences describing what the component is and does, written from the \
            datasheet's own description and feature list. Plain prose, no bullet points, no markdown. \
            Null if the excerpt does not describe the component.
            - specs: an array of objects {"key": "...", "value": "...", "page": <number>} where \
            page is the page of the excerpt the value was read from.
            """;

    private static final String IDENTIFY_FIELDS = """
            Nobody has told you which component this is: the document is all there is. \
            Read the title block and the front matter to identify it.

            Return ONLY a valid JSON object with no markdown formatting, no code blocks, no \
            explanation, with exactly these fields:
            - mpn: the manufacturer part number this datasheet is for (string, required). Exactly \
            one part number, never a list and never a variant's full ordering code. Where the \
            document covers a family, give the member the uploaded filename names; if the filename \
            names none of them, give the first part number of the title block.
            - manufacturer: the manufacturer whose document this is (string or null)
            - shortDescription: the document's own one-line title for the part, \
            e.g. "Single bipolar timer" (string or null)
            - category: component category such as "Transistors" or "Logic ICs" (string or null)
            - details: 2-5 sentences describing what the component is and does, written from the \
            datasheet's own description and feature list. Plain prose, no bullet points, no markdown. \
            Null if the excerpt does not describe the component.
            - specs: an array of objects {"key": "...", "value": "...", "page": <number>} where \
            page is the page of the excerpt the value was read from.
            """;

    private static final String SPEC_KEY_RULES = """
            IMPORTANT: for spec keys you MUST use EXACTLY these predefined keys when applicable. \
            Each entry below is the exact key to use, followed by a human-readable title in \
            parentheses (a hint only — do NOT use the title as the key). \
            Use only the numeric value without repeating the unit that is already in the key:
            %s

            For SELECT-type specs, use one of the allowed option values listed in parentheses. \
            For NUMBER specs with multiple unit options shown after the name, append the unit to \
            the value (e.g. "Capacitance: 100 nF" becomes value "100 nF"). \
            For NUMBER specs with a single unit, give the numeric value alone. \
            If a value the datasheet gives has no matching key above, still return it under a short \
            lowercase key of your own — an unrecognised field is kept and can be promoted to a real \
            field later.
            """;

    private static final String READING_RULES = """
            Rules that matter more than completeness:
            - Take values ONLY from the excerpt. Do not add what you know about this part from \
            elsewhere, and do not guess.
            - Prefer recommended operating conditions and typical characteristics over absolute \
            maximum ratings. Where you use an absolute maximum, say so in the value \
            (e.g. "40 (abs max)").
            - Many datasheets cover a family. Extract only values that apply to the exact part in \
            question; skip a parameter that differs across the family unless the excerpt shows \
            which row is that part's.
            - Where a parameter has min/typ/max columns, give the typical value; where there is no \
            typical, give the range as "min..max".
            - Return an empty specs array rather than a speculative one.
            """;

    private final PartService partService;
    private final PartAttachmentRepository partAttachmentRepository;
    private final DatasheetAnalyzer datasheetAnalyzer;
    private final SpecDefinitionService specDefinitionService;
    private final SpecFieldCatalog specFieldCatalog;
    private final AiCredentialsService aiCredentials;
    private final ObjectMapper objectMapper;

    /**
     * The long-timeout template. A 20k-token extraction runs well past the 30 s read timeout on the
     * shared {@code restTemplate}, which would surface as a truncated read rather than an error.
     * Spelled as an explicit constructor because {@code @RequiredArgsConstructor} does not copy the
     * qualifier onto the generated parameter and the wrong bean would be injected silently.
     */
    private final RestTemplate restTemplate;

    @Value("${anthropic.pricing.input-per-mtok:1.00}")
    private double inputPerMTok;

    @Value("${anthropic.pricing.output-per-mtok:5.00}")
    private double outputPerMTok;

    public DatasheetSpecExtractionService(PartService partService,
                                          PartAttachmentRepository partAttachmentRepository,
                                          DatasheetAnalyzer datasheetAnalyzer,
                                          SpecDefinitionService specDefinitionService,
                                          SpecFieldCatalog specFieldCatalog,
                                          AiCredentialsService aiCredentials,
                                          ObjectMapper objectMapper,
                                          @Qualifier("aiDocumentRestTemplate") RestTemplate restTemplate) {
        this.partService = partService;
        this.partAttachmentRepository = partAttachmentRepository;
        this.datasheetAnalyzer = datasheetAnalyzer;
        this.specDefinitionService = specDefinitionService;
        this.specFieldCatalog = specFieldCatalog;
        this.aiCredentials = aiCredentials;
        this.objectMapper = objectMapper;
        this.restTemplate = restTemplate;
    }

    /**
     * Extract from one stored datasheet. {@code attachmentId} may be null, in which case the part's
     * first stored datasheet is used — the common case, since most parts have exactly one.
     */
    public DatasheetExtractionDTO extract(Long partId, Long attachmentId) {
        // Through PartService so the part is scoped to the current organisation: another tenant's
        // part is reported as not found, and with it the attachment.
        Part part = partService.requirePart(partId);
        PartAttachment attachment = resolveAttachment(partId, attachmentId);

        byte[] data = attachment.getData();
        if (!PdfBytes.looksLikePdf(data)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "That attachment is not a PDF, so there is nothing to read.");
        }

        DatasheetAnalyzer.Analysis analysis = datasheetAnalyzer.analyze(data);
        if (analysis.route() == DatasheetAnalyzer.Route.UNUSABLE) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "That PDF could not be read: " + analysis.error());
        }
        if (analysis.route() == DatasheetAnalyzer.Route.NO_TEXT_LAYER) {
            // Refuse before spending anything: a scan has no text to send.
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "This datasheet is a scan with no text layer — there is no text to read. "
                            + "Reading the pages as images is not supported yet; the specifications "
                            + "have to be entered by hand, or a text PDF of the same part found.");
        }

        List<String> pages = pageTexts(data);
        String excerpt = buildExcerpt(pages);
        if (excerpt.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "No readable text could be taken from this PDF.");
        }

        Extracted extracted = callModel(part, attachment, analysis, excerpt);

        return DatasheetExtractionDTO.builder()
                .attachmentId(attachment.getId())
                .filename(attachment.getFilename())
                .route(analysis.route().name())
                .pages(analysis.pages())
                .headings(List.copyOf(analysis.headings()))
                .excerptChars(excerpt.length())
                .details(extracted.details())
                .specs(extracted.specs())
                .build();
    }

    /**
     * Identify the component an uploaded datasheet is for, and read its data — Quick Add's third
     * source, for when neither the catalogue, the cache, a web search nor a pasted page finds it.
     *
     * <p>The mirror image of {@link #extract}: there the part is known and the document fills in
     * its values; here the document is all there is and has to name the part as well. Everything
     * after that is the same machinery — the same excerpt, the same spec keys, the same reading
     * rules — because the difference is what we know going in, not how a datasheet is read.
     *
     * <p>Writes nothing and stores nothing: the file is read in memory and the answer is a
     * proposal. The caller confirms it on the Quick Add form and, when the part is created, uploads
     * the same file as its datasheet attachment. Doing it in that order means a PDF the user
     * abandons at the confirm step leaves nothing behind.
     *
     * <p>Costs what an extraction costs — a fraction of a cent, no web searches — and refuses the
     * same scanned PDFs, for the same reason: there is no text to send.
     */
    public PartSearchResultDTO identify(byte[] data, String filename) {
        if (!PdfBytes.looksLikePdf(data)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "That file is not a PDF, so there is nothing to read.");
        }

        DatasheetAnalyzer.Analysis analysis = datasheetAnalyzer.analyze(data);
        if (analysis.route() == DatasheetAnalyzer.Route.UNUSABLE) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "That PDF could not be read: " + analysis.error());
        }
        if (analysis.route() == DatasheetAnalyzer.Route.NO_TEXT_LAYER) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "This datasheet is a scan with no text layer — there is no text to read. "
                            + "Reading the pages as images is not supported yet; add the part by "
                            + "hand, or find a text PDF of the same part.");
        }

        String excerpt = buildExcerpt(pageTexts(data));
        if (excerpt.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "No readable text could be taken from this PDF.");
        }

        SpecFieldCatalog.Fields fields = specFieldCatalog.render();
        String system = buildSystem(IDENTIFY_FIELDS, fields);
        // The filename is worth naming: it is frequently the part number, and where the title block
        // is a family name it is the only hint of which member was downloaded.
        String user = "This datasheet was uploaded as \"" + filename + "\". "
                + "Identify the component it is for and extract its data.\n\n"
                + "Datasheet excerpt follows. Page numbers are marked as [page N]; \"[…]\" "
                + "marks omitted text.\n\n" + excerpt;

        long startedAt = System.currentTimeMillis();
        ResponseEntity<String> response = callAnthropic(system, user,
                "Datasheet identification request failed");

        JsonNode obj;
        try {
            obj = responseObject(response.getBody());
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Failed to parse the identification response: " + e.getMessage());
        }

        String mpn = obj == null ? "" : obj.path("mpn").asText("").strip();
        if (mpn.isBlank()) {
            // Not an error of ours, and not worth dressing up as an empty result: the user picked
            // this file believing it was a datasheet, and needs to know it did not read as one.
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Could not tell which component this document is for — its front matter names "
                            + "no part number. Is it a datasheet?");
        }

        Extracted extracted = detailsAndSpecs(obj);
        // The search DTO carries specs as "key: value" strings; canonicalisation already happened.
        List<String> specs = new ArrayList<>(extracted.specs().size());
        for (ExtractedSpecDTO spec : extracted.specs()) {
            specs.add(spec.key() + ": " + spec.value());
        }

        logUsage("datasheet-identify", mpn, filename, response.getBody(), analysis, fields, excerpt,
                specs.size(), System.currentTimeMillis() - startedAt);

        return PartSearchResultDTO.builder()
                .mpn(mpn)
                .manufacturer(nullIfBlank(obj.path("manufacturer").asText(null)))
                .shortDescription(nullIfBlank(obj.path("shortDescription").asText(null)))
                .category(nullIfBlank(obj.path("category").asText(null)))
                .details(extracted.details())
                .specs(specs)
                .build();
    }

    private static String nullIfBlank(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    private PartAttachment resolveAttachment(Long partId, Long attachmentId) {
        if (attachmentId != null) {
            PartAttachment a = partAttachmentRepository.findByIdAndPartId(attachmentId, partId)
                    .orElseThrow(() -> new EntityNotFoundException("Attachment not found: " + attachmentId));
            if (a.getType() != AttachmentType.DATASHEET) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "That attachment is not a datasheet.");
            }
            return a;
        }
        List<PartAttachment> stored = partAttachmentRepository
                .findByPartIdAndTypeOrderByDisplayOrder(partId, AttachmentType.DATASHEET);
        if (stored.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "This part has no stored datasheet. Upload one, or download it from the "
                            + "datasheet URL first.");
        }
        return stored.get(0);
    }

    // ── Text extraction and excerpting ──────────────────────────────────────────

    /** Page-by-page text, so an excerpt can carry the page number each span came from. */
    private List<String> pageTexts(byte[] data) {
        try (PDDocument doc = Loader.loadPDF(data)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            List<String> pages = new ArrayList<>(doc.getNumberOfPages());
            for (int i = 1; i <= doc.getNumberOfPages(); i++) {
                stripper.setStartPage(i);
                stripper.setEndPage(i);
                pages.add(stripper.getText(doc));
            }
            return pages;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Could not extract text from this PDF: " + e.getMessage());
        }
    }

    /**
     * Front matter plus a window from each parametric heading, page-marked and capped.
     *
     * <p>Spans are collected as character ranges over the concatenated document and then merged, so
     * two headings a paragraph apart produce one span rather than two overlapping copies of the
     * same text. Merging before rendering is what keeps a datasheet with a dozen "… Characteristics"
     * subheadings from paying for each of them.
     */
    String buildExcerpt(List<String> pages) {
        // Concatenate with an index of where each page starts, so a character offset maps back to a
        // page number.
        StringBuilder all = new StringBuilder();
        List<Integer> pageStart = new ArrayList<>(pages.size());
        for (String page : pages) {
            pageStart.add(all.length());
            all.append(page).append('\n');
        }
        String text = all.toString();

        List<int[]> spans = new ArrayList<>();

        // 1. Front matter — page 1, and page 2 too when page 1 is barely more than a title block.
        int frontEnd = 0;
        for (int i = 0; i < Math.min(FRONT_MATTER_PAGES, pages.size()); i++) {
            frontEnd = pageStart.get(i) + pages.get(i).length();
            if (pages.get(i).replaceAll("\\s", "").length() >= THIN_PAGE_CHARS) break;
        }
        if (frontEnd > 0) spans.add(new int[]{0, frontEnd});

        // 2. A window from each parametric heading onwards.
        Matcher m = DatasheetAnalyzer.headingPattern().matcher(text);
        while (m.find()) {
            int start = m.start();
            spans.add(new int[]{start, Math.min(text.length(), start + HEADING_WINDOW_CHARS)});
        }

        // 3. Merge overlapping/adjacent spans, then render with page markers, stopping at the cap.
        spans.sort((a, b) -> Integer.compare(a[0], b[0]));
        List<int[]> merged = new ArrayList<>();
        for (int[] span : spans) {
            if (!merged.isEmpty() && span[0] <= merged.get(merged.size() - 1)[1]) {
                int[] last = merged.get(merged.size() - 1);
                last[1] = Math.max(last[1], span[1]);
            } else {
                merged.add(new int[]{span[0], span[1]});
            }
        }

        StringBuilder out = new StringBuilder();
        for (int[] span : merged) {
            if (out.length() >= MAX_EXCERPT_CHARS) {
                out.append("\n[… excerpt truncated ─ the document continues]\n");
                break;
            }
            int end = Math.min(span[1], span[0] + (MAX_EXCERPT_CHARS - out.length()));
            if (!out.isEmpty()) out.append("\n[…]\n");
            out.append("[page ").append(pageOf(pageStart, span[0])).append("]\n")
                    .append(text, span[0], end);
        }
        return out.toString();
    }

    /** 1-based page holding the given character offset. */
    private static int pageOf(List<Integer> pageStart, int offset) {
        for (int i = pageStart.size() - 1; i >= 0; i--) {
            if (offset >= pageStart.get(i)) return i + 1;
        }
        return 1;
    }

    // ── The model call ──────────────────────────────────────────────────────────

    private record Extracted(String details, List<ExtractedSpecDTO> specs) {}

    private Extracted callModel(Part part, PartAttachment attachment,
                                DatasheetAnalyzer.Analysis analysis, String excerpt) {
        SpecFieldCatalog.Fields fields = specFieldCatalog.render();
        String system = buildSystem(EXTRACT_FIELDS, fields);

        // Naming the part explicitly matters: a datasheet frequently covers a family, and without
        // this the model averages the family rather than reading this part's row.
        StringBuilder user = new StringBuilder();
        user.append("Extract the data for part number: ").append(part.getPartNumber()).append('\n');
        if (part.getMpn() != null && !part.getMpn().isBlank()) {
            user.append("Manufacturer part number: ").append(part.getMpn()).append('\n');
        }
        if (part.getManufacturer() != null && !part.getManufacturer().isBlank()) {
            user.append("Manufacturer: ").append(part.getManufacturer()).append('\n');
        }
        user.append("\nDatasheet excerpt follows. Page numbers are marked as [page N]; \"[…]\" "
                + "marks omitted text.\n\n").append(excerpt);

        long startedAt = System.currentTimeMillis();
        ResponseEntity<String> response = callAnthropic(system, user.toString(),
                "Datasheet extraction request failed");

        Extracted extracted;
        try {
            extracted = parseResponse(response.getBody());
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Failed to parse the extraction response: " + e.getMessage());
        }

        logUsage("datasheet-extract", part.getPartNumber(), String.valueOf(attachment.getId()),
                response.getBody(), analysis, fields, excerpt, extracted.specs().size(),
                System.currentTimeMillis() - startedAt);
        return extracted;
    }

    /** The four prompt blocks, assembled for one of the two readers. */
    private static String buildSystem(String fieldsBlock, SpecFieldCatalog.Fields fields) {
        return READER_INTRO + "\n" + fieldsBlock + "\n"
                + String.format(SPEC_KEY_RULES, fields.text()) + "\n" + READING_RULES;
    }

    /**
     * One Messages API call. Shared by both readers so they get the same long read timeout — an
     * excerpt this size runs past the 30 s of the ordinary template, which surfaces as a truncated
     * read rather than an error.
     */
    private ResponseEntity<String> callAnthropic(String system, String user, String what) {
        // The organisation's own key and model — it pays for this call. 503 when it has none, saying
        // which of the unusable states it is in.
        AiCredentialsService.Credentials credentials = aiCredentials.require();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-api-key", credentials.apiKey());
        headers.set("anthropic-version", API_VERSION);

        Map<String, Object> body = Map.of(
                "model", credentials.model(),
                "max_tokens", 4096,
                "system", system,
                "messages", List.of(Map.of("role", "user", "content", user))
        );
        try {
            ResponseEntity<String> response = restTemplate.exchange(API_URL, HttpMethod.POST,
                    new HttpEntity<>(body, headers), String.class);
            aiCredentials.noteSuccess();
            return response;
        } catch (Exception e) {
            throw aiCredentials.translate(e, what);
        }
    }

    private Extracted parseResponse(String body) throws Exception {
        JsonNode obj = responseObject(body);
        return obj == null ? new Extracted(null, List.of()) : detailsAndSpecs(obj);
    }

    /** The JSON object the model answered with, or null when it answered with something else. */
    private JsonNode responseObject(String body) throws Exception {
        JsonNode root = objectMapper.readTree(body);
        if (root.has("error")) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Anthropic API error: " + root.path("error").path("message").asText("Unknown error"));
        }

        String text = null;
        for (JsonNode item : root.path("content")) {
            if ("text".equals(item.path("type").asText(""))) {
                text = item.path("text").asText("").strip();
            }
        }
        if (text == null || text.isBlank()) return null;

        // Same defensive unwrapping as the part search: the model may fence the JSON or prepend prose.
        int fence = text.indexOf("```");
        if (fence >= 0) {
            text = text.substring(fence)
                    .replaceFirst("^```(?:json)?\\s*", "")
                    .replaceFirst("```\\s*$", "")
                    .strip();
        } else {
            int brace = text.indexOf('{');
            if (brace < 0) return null;
            if (brace > 0) text = text.substring(brace);
        }

        JsonNode obj = objectMapper.readTree(text);
        return obj.isObject() ? obj : null;
    }

    private Extracted detailsAndSpecs(JsonNode obj) {
        // Canonicalize through the alias table exactly as every other intake path does, so a value
        // arriving under a source's own name lands on the spec it belongs to rather than creating a
        // duplicate field. Done on a map because canonicalizeKeys works on one; the page numbers are
        // carried alongside and re-attached after.
        Map<String, Object> raw = new LinkedHashMap<>();
        Map<String, Integer> pageByKey = new LinkedHashMap<>();
        for (JsonNode spec : obj.path("specs")) {
            String key = spec.path("key").asText("").strip();
            String value = spec.path("value").asText("").strip();
            if (key.isBlank() || value.isBlank()) continue;
            raw.put(key, value);
            JsonNode page = spec.path("page");
            if (page.isNumber()) pageByKey.put(key, page.asInt());
        }
        Map<String, Object> canonical = specDefinitionService.canonicalizeKeys(raw);

        List<ExtractedSpecDTO> specs = new ArrayList<>(canonical.size());
        canonical.forEach((key, value) -> specs.add(ExtractedSpecDTO.builder()
                .key(key)
                .value(String.valueOf(value))
                .page(pageByKey.get(key))
                .build()));

        JsonNode details = obj.path("details");
        String detailsText = (details.isNull() || details.isMissingNode())
                ? null : details.asText("").strip();
        return new Extracted((detailsText == null || detailsText.isBlank()) ? null : detailsText, specs);
    }

    /**
     * One INFO line per extraction, mirroring {@code ai-part-search} so the two can be compared in
     * the same log. No web-search figure: this path runs none, which is the whole point of it.
     */
    private void logUsage(String action, String subject, String source, String body,
                          DatasheetAnalyzer.Analysis analysis, SpecFieldCatalog.Fields fields,
                          String excerpt, int specCount, long millis) {
        try {
            JsonNode root = objectMapper.readTree(body);
            // Off the response, not the request: this is the model that actually served the call,
            // which is what the cost estimate below should be read against.
            String model = root.path("model").asText("unknown");
            JsonNode usage = root.path("usage");
            long input = usage.path("input_tokens").asLong(0);
            long output = usage.path("output_tokens").asLong(0);
            double cost = (input * inputPerMTok + output * outputPerMTok) / 1_000_000d;

            log.info("{} model={} part={} source={} route={} docPages={} "
                            + "excerptChars={} specDefs={} inTok={} outTok={} specs={} ms={} estCostUsd={}",
                    action, model, subject, source, analysis.route(), analysis.pages(),
                    excerpt.length(), fields.count(), input, output, specCount, millis,
                    String.format("%.4f", cost));
        } catch (Exception e) {
            log.debug("Could not log datasheet extraction usage: {}", e.toString());
        }
    }
}
