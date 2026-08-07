package com.clele.parts.service;

import com.clele.parts.dto.CategorizationStatusDTO;
import com.clele.parts.model.Category;
import com.clele.parts.model.Part;
import com.clele.parts.repository.CategoryRepository;
import com.clele.parts.repository.PartRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Auto-assigns every part to a leaf category using a locally installed AI (Ollama / llama3.2).
 * Runs as a single background job (one part per Ollama call) so it survives past HTTP timeouts;
 * the UI polls {@link #status()} for progress. Entirely offline — no cloud calls.
 */
@Service
@RequiredArgsConstructor
public class PartCategorizationService {

    private static final Logger log = LoggerFactory.getLogger(PartCategorizationService.class);

    private static final String SYSTEM_PROMPT_TEMPLATE = """
            You are an electronics inventory classifier. Choose the single best-fitting category \
            for the given electronic part from the numbered list below.

            Respond with ONLY a JSON object, no markdown and no explanation: {"categoryId": <number>}
            Use the exact numeric id of the most specific matching category. \
            If no category fits, respond {"categoryId": null}.

            Categories:
            %s
            """;

    private final PartRepository partRepository;
    private final CategoryRepository categoryRepository;
    private final CurrentOrganisationService currentOrganisationService;
    private final RestTemplate ollamaRestTemplate;
    private final ObjectMapper objectMapper;
    private final PlatformTransactionManager txManager;

    @Value("${ollama.base-url:http://localhost:11434}")
    private String baseUrl;

    @Value("${ollama.model:llama3.2}")
    private String model;

    /**
     * Context window to ask Ollama for. Must hold the whole system prompt (the leaf-category
     * catalogue) plus the part description, or Ollama <em>silently</em> truncates the prompt to
     * roughly half its default 4096 and classifies against a fragment of the taxonomy — see
     * {@link #warnIfPromptExceedsContext}. Raise this if the taxonomy outgrows it.
     */
    @Value("${ollama.num-ctx:8192}")
    private int numCtx;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "part-categorizer");
        t.setDaemon(true);
        return t;
    });

    // Job state (single job at a time).
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile boolean onlyUncategorized;
    /** The organisation the running job works in, captured on the request thread in {@link #start}. */
    private volatile Long organisationId;
    private volatile int total;
    private volatile int processed;
    private volatile int assigned;
    private volatile int skipped;
    private volatile LocalDateTime startedAt;
    private volatile LocalDateTime finishedAt;
    private volatile String lastError;

    /**
     * Starts the background job; throws if one is already running.
     *
     * @param onlyUncategorized when true, only parts without a category are processed
     *                          (leaves existing assignments intact); otherwise all parts are
     *                          (re)categorized, overwriting existing assignments.
     */
    public CategorizationStatusDTO start(boolean onlyUncategorized) {
        if (!running.compareAndSet(false, true)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.CONFLICT, "Auto-categorization is already running");
        }
        this.onlyUncategorized = onlyUncategorized;
        // The worker thread has no HTTP session, so the organisation is resolved here — on the
        // request thread — and carried into the job explicitly.
        this.organisationId = currentOrganisationService.currentId();
        total = processed = assigned = skipped = 0;
        startedAt = LocalDateTime.now();
        finishedAt = null;
        lastError = null;
        executor.submit(this::run);
        return status();
    }

    public CategorizationStatusDTO status() {
        return CategorizationStatusDTO.builder()
                .running(running.get())
                .total(total)
                .processed(processed)
                .assigned(assigned)
                .skipped(skipped)
                .startedAt(startedAt)
                .finishedAt(finishedAt)
                .lastError(lastError)
                .build();
    }

    private void run() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        try {
            List<Category> categories = categoryRepository.findByOrganisationIdOrderByName(organisationId);
            Map<Long, Category> byId = new HashMap<>();
            Set<Long> parentIds = new HashSet<>();
            for (Category c : categories) {
                byId.put(c.getId(), c);
                if (c.getParent() != null) {
                    parentIds.add(c.getParent().getId());
                }
            }
            // Leaf categories = those that are not the parent of any other category.
            List<Category> leaves = categories.stream()
                    .filter(c -> !parentIds.contains(c.getId()))
                    .toList();
            Set<Long> leafIds = new HashSet<>();
            StringBuilder catalogue = new StringBuilder();
            for (Category c : leaves) {
                leafIds.add(c.getId());
                catalogue.append(c.getId()).append(": ").append(breadcrumb(c, byId));
                if (c.getDescription() != null && !c.getDescription().isBlank()) {
                    catalogue.append(" — ").append(c.getDescription());
                }
                catalogue.append('\n');
            }
            String systemPrompt = String.format(SYSTEM_PROMPT_TEMPLATE, catalogue);
            warnIfPromptExceedsContext(systemPrompt, leafIds.size());

            List<Part> parts = onlyUncategorized
                    ? partRepository.findByOrganisationIdAndCategoryIsNull(organisationId)
                    : partRepository.findByOrganisationId(organisationId);
            total = parts.size();
            log.info("Auto-categorization started: {} parts ({}), {} leaf categories",
                    total, onlyUncategorized ? "uncategorized only" : "all", leafIds.size());

            for (Part part : parts) {
                try {
                    Long catId = classify(systemPrompt, part);
                    if (catId != null && leafIds.contains(catId)) {
                        tx.executeWithoutResult(s -> {
                            Part p = partRepository.findById(part.getId()).orElseThrow();
                            p.setCategory(categoryRepository.getReferenceById(catId));
                            partRepository.save(p);
                        });
                        assigned++;
                    } else {
                        skipped++;
                    }
                } catch (ResourceAccessException e) {
                    // Ollama unreachable — abort the whole run rather than failing every part.
                    lastError = "Ollama not reachable at " + baseUrl + ": " + e.getMessage();
                    log.error(lastError);
                    break;
                } catch (Exception e) {
                    skipped++;
                    lastError = "Part " + part.getPartNumber() + ": " + e.getMessage();
                    log.warn("Categorization failed for part {}: {}", part.getPartNumber(), e.getMessage());
                }
                processed++;
            }
            log.info("Auto-categorization complete: processed {}, assigned {}, skipped {}",
                    processed, assigned, skipped);
        } catch (Exception e) {
            lastError = e.getMessage();
            log.error("Auto-categorization aborted", e);
        } finally {
            finishedAt = LocalDateTime.now();
            running.set(false);
        }
    }

    /**
     * Warns when the catalogue is close to outgrowing {@link #numCtx}.
     *
     * <p>Ollama does not report an over-long prompt as an error: it truncates to roughly half the
     * context (reserving the rest for the reply), keeps the <em>tail</em>, and answers normally. The
     * head it drops is the instruction block, so the model both loses most of the taxonomy and stops
     * being told what to reply — yet every id it returns still passes the leaf-set check and gets
     * saved. Nothing downstream can tell that apart from a good run, so the only place it can be
     * caught is here, before the first call.
     *
     * <p>Four characters per token is a deliberate under-estimate for this text (breadcrumbs are
     * punctuation-heavy and tokenize worse), so the warning fires early rather than late.
     */
    private void warnIfPromptExceedsContext(String systemPrompt, int leafCount) {
        int estimatedTokens = systemPrompt.length() / 4;
        // The reply is tiny, but leave room for the part description the user message carries.
        int budget = numCtx - 512;
        if (estimatedTokens >= budget) {
            log.warn("Category prompt is ~{} tokens for {} leaf categories but ollama.num-ctx is {}"
                            + " — Ollama will silently truncate it and classify against a fragment"
                            + " of the taxonomy. Raise ollama.num-ctx.",
                    estimatedTokens, leafCount, numCtx);
        }
    }

    /** Calls Ollama once for a part and returns the chosen category id (or null). */
    private Long classify(String systemPrompt, Part part) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> body = Map.of(
                "model", model,
                "stream", false,
                "format", "json",
                "options", Map.of("temperature", 0, "num_ctx", numCtx),
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", describe(part))
                )
        );

        ResponseEntity<String> response = ollamaRestTemplate.exchange(
                baseUrl + "/api/chat", HttpMethod.POST, new HttpEntity<>(body, headers), String.class);

        try {
            JsonNode root = objectMapper.readTree(response.getBody());
            if (root.hasNonNull("error")) {
                throw new RuntimeException("Ollama error: " + root.path("error").asText());
            }
            String content = root.path("message").path("content").asText("").strip();
            if (content.isBlank()) return null;
            JsonNode parsed = objectMapper.readTree(content);
            JsonNode idNode = parsed.path("categoryId");
            return idNode.isIntegralNumber() ? idNode.asLong() : null;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Bad Ollama response: " + e.getMessage(), e);
        }
    }

    /** Renders the part's identifying fields for the classifier prompt. */
    private String describe(Part part) {
        StringBuilder sb = new StringBuilder();
        sb.append("Part: ").append(part.getPartNumber());
        if (part.getMpn() != null) sb.append("\nMPN: ").append(part.getMpn());
        if (part.getManufacturer() != null) sb.append("\nManufacturer: ").append(part.getManufacturer());
        if (part.getDescription() != null) sb.append("\nDescription: ").append(part.getDescription());
        if (part.getFootprint() != null) sb.append("\nPackage: ").append(part.getFootprint());
        if (part.getSpecs() != null && !part.getSpecs().isEmpty()) {
            sb.append("\nSpecs: ");
            part.getSpecs().forEach((k, v) -> sb.append(k).append('=').append(v).append("; "));
        }
        return sb.toString();
    }

    /** Builds "Root > ... > Leaf" by walking the parent chain via the id map. */
    private String breadcrumb(Category category, Map<Long, Category> byId) {
        List<String> names = new ArrayList<>();
        Category current = category;
        while (current != null) {
            names.add(0, current.getName());
            Category parent = current.getParent();
            current = parent == null ? null : byId.get(parent.getId());
        }
        return String.join(" > ", names);
    }
}
