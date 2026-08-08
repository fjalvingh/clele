package com.clele.parts.service;

import com.clele.parts.dto.PartKitGenerationDTO;
import com.clele.parts.dto.PartKitUndoResultDTO;
import com.clele.parts.model.Location;
import com.clele.parts.model.Part;
import com.clele.parts.model.PartKitGeneration;
import com.clele.parts.model.PartKitGenerationItem;
import com.clele.parts.model.StockEntry;
import com.clele.parts.model.StockMovement;
import com.clele.parts.repository.PartKitGenerationRepository;
import com.clele.parts.repository.PartKitTemplateRepository;
import com.clele.parts.repository.PartRepository;
import com.clele.parts.repository.ProjectBomLineRepository;
import com.clele.parts.repository.ProjectPartRepository;
import com.clele.parts.repository.ProjectStockRepository;
import com.clele.parts.repository.StockEntryRepository;
import com.clele.parts.repository.StockMovementRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The history of "Generate parts" runs on a kit template, and undoing the last of them.
 *
 * <p>Generating a kit is the one action in this app that creates dozens of rows from a single
 * click — thirty parts, thirty stock movements, thirty attachment links. Getting the quantity or
 * the location wrong used to mean finding every one of them in the parts list by hand, with nothing
 * to say which parts had come from the run. Each run is now recorded (see {@link PartKitGeneration})
 * and the most recent one can be taken back whole.
 *
 * <h2>What "undoable" means</h2>
 *
 * <p>Undoing is not a general reversal — it is the narrow claim <em>nothing has happened since</em>,
 * and every condition below is checked before anything is deleted:
 *
 * <ul>
 *   <li><b>Only the newest run of the kit.</b> Undoing an earlier one would leave the later runs
 *       standing on parts and stock it had just removed.</li>
 *   <li><b>The stock is still exactly as the run left it</b>, per line: the movement it wrote is
 *       still the last thing that happened to that part anywhere, and the entry still holds
 *       {@code quantityBefore + quantityAdded}. A part consumed, moved, restocked or corrected since
 *       fails this — the run is no longer the last word on it.</li>
 *   <li><b>No part it created is used in a project</b> — on a project's BOM, pulled into a build, or
 *       matched to an imported BOM line. Those are decisions someone made about the part after it
 *       existed, and deleting it would silently unpick them.</li>
 *   <li><b>Nothing it made has vanished by another route.</b> A deleted part or movement nulls its
 *       pointer here rather than cascading, so a half-gone run is visible and is refused.</li>
 * </ul>
 *
 * <p>A refusal always names its reason (see {@link PartKitGenerationDTO#getUndoBlockedReason()}) —
 * a greyed-out button that will not say why is indistinguishable from a broken one.
 *
 * <h2>What it does not undo</h2>
 *
 * <p>Parts the run <em>found</em> rather than created are never deleted: they existed before it and
 * must outlive it. Only the stock it added to them comes off. Edits made to a generated part are not
 * checked for — a part that has been re-described is still deleted, because the test the design
 * commits to is about stock and project use, not about every field.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PartKitGenerationService {

    private final PartKitGenerationRepository generationRepository;
    private final PartKitTemplateRepository templateRepository;
    private final PartRepository partRepository;
    private final StockEntryRepository stockEntryRepository;
    private final StockMovementRepository stockMovementRepository;
    private final ProjectPartRepository projectPartRepository;
    private final ProjectStockRepository projectStockRepository;
    private final ProjectBomLineRepository projectBomLineRepository;
    private final PartAttachmentService partAttachmentService;
    private final CurrentOrganisationService currentOrganisationService;

    // ------------------------------------------------------------------
    // History
    // ------------------------------------------------------------------

    public List<PartKitGenerationDTO> findByTemplate(Long templateId) {
        requireTemplate(templateId);
        List<PartKitGeneration> runs = generationRepository.findByTemplateIdNewestFirst(templateId);
        Long latestId = generationRepository.findLatestId(templateId);
        return runs.stream().map(g -> toDTO(g, latestId)).toList();
    }

    // ------------------------------------------------------------------
    // Undo
    // ------------------------------------------------------------------

    /**
     * Take back one generation run: remove the stock it added, and delete the parts it created.
     *
     * <p>One transaction for the whole thing, for the same reason generating is: a half-undone run
     * leaves the catalogue in a state nothing describes.
     */
    @Transactional
    public PartKitUndoResultDTO undo(Long templateId, Long generationId) {
        requireTemplate(templateId);
        PartKitGeneration generation = generationRepository
                .findByIdAndTemplateId(generationId, templateId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Generation not found: " + generationId));

        String blocked = undoBlockedReason(generation, generationRepository.findLatestId(templateId));
        if (blocked != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, blocked);
        }

        // Read everything out of the items before the generation row goes: once it is deleted the
        // entities are detached and their lazy associations cannot be resolved.
        record Reversal(Long partId, String partNumber, boolean partCreated, int quantity,
                        Long movementId, Long locationId, Integer quantityBefore,
                        BigDecimal unitPriceBefore) {}
        List<Reversal> reversals = new ArrayList<>();
        for (PartKitGenerationItem item : generation.getItems()) {
            StockMovement movement = item.getMovement();
            reversals.add(new Reversal(
                    item.getPart() == null ? null : item.getPart().getId(),
                    item.getPart() == null ? null : item.getPart().getPartNumber(),
                    item.isPartCreated(),
                    item.getQuantityAdded(),
                    movement == null ? null : movement.getId(),
                    movement == null ? null : movement.getLocation().getId(),
                    item.getQuantityBefore(),
                    item.getUnitPriceBefore()));
        }

        // The record goes first. Its items point at the parts and movements about to be deleted, and
        // clearing those pointers by hand only to delete the rows a moment later is work for nothing.
        generationRepository.delete(generation);
        generationRepository.flush();

        int stockRemoved = 0;
        int partsDeleted = 0;
        int partsKept = 0;
        List<String> deletedPartNumbers = new ArrayList<>();

        for (Reversal r : reversals) {
            if (r.movementId() != null) {
                stockMovementRepository.deleteById(r.movementId());
                stockMovementRepository.flush();
                stockRemoved += r.quantity();

                StockEntry entry = stockEntryRepository
                        .findByPartIdAndLocationId(r.partId(), r.locationId()).orElse(null);
                if (entry != null) {
                    int remaining = entry.getQuantity() - r.quantity();
                    if (remaining == 0
                            && stockMovementRepository.countByPartIdAndLocationId(
                                    r.partId(), r.locationId()) == 0) {
                        // No stock and no history left at this location: the run created the row, so
                        // undoing it removes the row rather than leaving a phantom zero behind.
                        stockEntryRepository.delete(entry);
                    } else {
                        entry.setQuantity(remaining);
                        // The weighted-average cost the add recalculated is not invertible, which is
                        // why what it replaced was recorded at the time.
                        entry.setUnitPrice(r.unitPriceBefore());
                        stockEntryRepository.save(entry);
                    }
                }
            }

            if (r.partCreated() && r.partId() != null) {
                // Same sequence as PartService.delete: stock_entry has no ON DELETE CASCADE, and the
                // attachment content survives only where another part or a kit template still holds
                // it — the kit's own photos are shared with the template that handed them out.
                stockEntryRepository.deleteByPartId(r.partId());
                partAttachmentService.deleteAllForPart(r.partId());
                partRepository.deleteById(r.partId());
                partsDeleted++;
                deletedPartNumbers.add(r.partNumber());
            } else if (r.partId() != null) {
                partsKept++;
            }
        }

        log.info("Undid kit generation {} of template {}: {} parts deleted, {} kept, {} units removed",
                generationId, templateId, partsDeleted, partsKept, stockRemoved);

        return PartKitUndoResultDTO.builder()
                .generationId(generationId)
                .partsDeleted(partsDeleted)
                .partsKept(partsKept)
                .stockRemoved(stockRemoved)
                .deletedPartNumbers(deletedPartNumbers)
                .build();
    }

    /**
     * Why this run cannot be undone, in the user's terms, or null when it can.
     *
     * <p>Every check is a way the world can have moved on since the run. They are phrased for the
     * screen rather than for a log, because this string is the only explanation a disabled Undo
     * button gets.
     */
    private String undoBlockedReason(PartKitGeneration generation, Long latestId) {
        if (!Objects.equals(generation.getId(), latestId)) {
            return "Only the most recent generation of a kit can be undone — "
                    + "a later run has been made since this one";
        }

        for (PartKitGenerationItem item : generation.getItems()) {
            String value = item.getValue();
            Part part = item.getPart();
            if (part == null) {
                return "The part generated for '" + value + "' has since been deleted";
            }

            if (item.getQuantityAdded() > 0) {
                StockMovement movement = item.getMovement();
                if (movement == null) {
                    return "The stock movement for '" + value + "' is no longer there";
                }
                Long latestMovement = stockMovementRepository.findLatestIdByPartId(part.getId());
                if (!Objects.equals(movement.getId(), latestMovement)) {
                    return "Stock for " + part.getPartNumber() + " ('" + value
                            + "') has changed since it was generated";
                }
                Location location = movement.getLocation();
                StockEntry entry = stockEntryRepository
                        .findByPartIdAndLocationId(part.getId(), location.getId()).orElse(null);
                int expected = (item.getQuantityBefore() == null ? 0 : item.getQuantityBefore())
                        + item.getQuantityAdded();
                if (entry == null || entry.getQuantity() != expected) {
                    return "Stock for " + part.getPartNumber() + " ('" + value + "') at "
                            + location.breadcrumb() + " is no longer as it was generated";
                }
            } else if (item.isPartCreated() && stockMovementRepository.countByPartId(part.getId()) > 0) {
                // The run added no stock, so the part it created should have no ledger at all.
                return "Stock has been added to " + part.getPartNumber() + " ('" + value
                        + "') since it was generated";
            }

            if (item.isPartCreated()) {
                String use = projectUse(part);
                if (use != null) {
                    return part.getPartNumber() + " ('" + value + "') " + use;
                }
            }
        }
        return null;
    }

    /** How a part is used in a project, or null when it is not. */
    private String projectUse(Part part) {
        if (projectPartRepository.existsByPartId(part.getId())) {
            return "is on a project's BOM";
        }
        if (projectStockRepository.existsByPartId(part.getId())) {
            return "has been pulled into a project";
        }
        if (projectBomLineRepository.existsByPartId(part.getId())) {
            return "is matched to an imported BOM line";
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private void requireTemplate(Long templateId) {
        templateRepository
                .findByIdAndOrganisationId(templateId, currentOrganisationService.currentId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Kit template not found: " + templateId));
    }

    private PartKitGenerationDTO toDTO(PartKitGeneration g, Long latestId) {
        String blocked = undoBlockedReason(g, latestId);
        return PartKitGenerationDTO.builder()
                .id(g.getId())
                .generatedAt(g.getGeneratedAt())
                .generatedByName(g.getGeneratedBy() == null ? null : g.getGeneratedBy().getFullName())
                .quantityPerValue(g.getQuantityPerValue())
                .unitPrice(g.getUnitPrice())
                .locationId(g.getLocation() == null ? null : g.getLocation().getId())
                .locationBreadcrumb(g.getLocation() == null ? null : g.getLocation().breadcrumb())
                .partsCreated(g.getPartsCreated())
                .partsFound(g.getPartsFound())
                .stockAdded(g.getStockAdded())
                .undoable(blocked == null)
                .undoBlockedReason(blocked)
                .lines(g.getItems().stream()
                        .map(i -> PartKitGenerationDTO.Line.builder()
                                .value(i.getValue())
                                .partId(i.getPart() == null ? null : i.getPart().getId())
                                .partNumber(i.getPart() == null ? null : i.getPart().getPartNumber())
                                .created(i.isPartCreated())
                                .quantityAdded(i.getQuantityAdded())
                                .build())
                        .toList())
                .build();
    }
}
