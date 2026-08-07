package com.clele.parts.service;

import com.clele.parts.dto.QuickAddRequest;
import com.clele.parts.dto.QuickAddResponseDTO;
import com.clele.parts.dto.StockEntryDTO;
import com.clele.parts.model.Category;
import com.clele.parts.model.Location;
import com.clele.parts.model.MovementType;
import com.clele.parts.model.Part;
import com.clele.parts.model.StockEntry;
import com.clele.parts.model.AttachmentType;
import com.clele.parts.repository.CategoryRepository;
import com.clele.parts.repository.PartAttachmentRepository;
import com.clele.parts.repository.LocationRepository;
import com.clele.parts.repository.PartRepository;
import com.clele.parts.repository.StockEntryRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
@Slf4j
public class QuickAddService {

    private final PartRepository partRepository;
    private final LocationRepository locationRepository;
    private final StockEntryRepository stockEntryRepository;
    private final CategoryRepository categoryRepository;
    private final PartService partService;
    private final CurrentUserService currentUserService;
    private final CurrentOrganisationService currentOrganisationService;
    private final StockMovementService stockMovementService;
    private final TagService tagService;
    private final SpecDefinitionService specDefinitionService;
    private final PartAttachmentService partAttachmentService;
    private final PartAttachmentRepository partAttachmentRepository;

    @Transactional
    public QuickAddResponseDTO quickAdd(QuickAddRequest request) {
        Long organisationId = currentOrganisationService.currentId();

        // Find or create part, within the current organisation only.
        Part part = partRepository
                .findByOrganisationIdAndPartNumber(organisationId, request.getPartNumber())
                .orElseGet(() -> createPart(request));

        // Load location
        Location location = locationRepository
                .findByIdAndOrganisationId(request.getLocationId(), organisationId)
                .orElseThrow(() -> new EntityNotFoundException("Location not found: " + request.getLocationId()));

        // Check for duplicate stock entry
        if (stockEntryRepository.existsByPartIdAndLocationId(part.getId(), location.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A stock entry already exists for this part/location combination");
        }

        // The funnel writes the INITIAL movement, creates the entry and checks the organisation.
        StockEntry saved = stockMovementService.apply(part, location, request.getQuantity(),
                request.getUnitPrice(), null, MovementType.INITIAL);
        saved = stockEntryRepository.save(saved);
        currentUserService.rememberLastLocation(location);

        StockEntryDTO stockEntryDTO = StockEntryDTO.builder()
                .id(saved.getId())
                .partId(part.getId())
                .partName(part.getPartNumber())
                .partNumber(part.getPartNumber())
                .locationId(location.getId())
                .locationName(location.getName())
                .locationBreadcrumb(location.breadcrumb())
                .quantity(saved.getQuantity())
                .unitPrice(saved.getUnitPrice())
                .build();

        return new QuickAddResponseDTO(partService.toDTO(part), stockEntryDTO);
    }

    /**
     * Pull the part's datasheet into {@code part_attachment}, best-effort.
     *
     * <p>The lookup already found a datasheet URL and it is stored on the part, but nothing ever
     * downloaded it — so the document behind it could disappear at any time, which is exactly what
     * happened to the ~98% of imported URLs that pointed at Octopart. Fetching it while it is known
     * good is the cheap moment.
     *
     * <p><b>Deliberately not called from inside {@link #quickAdd}</b>, which is
     * {@code @Transactional}. A vendor fetch takes seconds and would hold a database connection
     * open for the duration, and worse: {@code uploadFromUrl} is itself transactional, so joining
     * the caller's transaction means a failed download marks it rollback-only and the part
     * creation is lost even if the exception is caught. Running after the commit keeps a dead link
     * from costing the user their part. Same reasoning as the Partsbox importer, which downloads
     * images outside its load transaction.
     *
     * <p>Every failure is swallowed at INFO: an unreachable, moved or non-PDF datasheet is an
     * ordinary outcome, not something to interrupt the user for. The URL stays on the part either
     * way, so nothing is lost and the Documents card's "Download from URL" can retry by hand.
     */
    public void attachDatasheetBestEffort(Long partId, String datasheetUrl) {
        if (partId == null || datasheetUrl == null || datasheetUrl.isBlank()) {
            return;
        }
        if (partAttachmentRepository.countByPartIdAndType(partId, AttachmentType.DATASHEET) > 0) {
            return;
        }
        try {
            partAttachmentService.uploadFromUrl(partId, datasheetUrl, AttachmentType.DATASHEET);
            log.info("Stored datasheet for part {} from {}", partId, datasheetUrl);
        } catch (Exception e) {
            log.info("Could not store datasheet for part {} from {}: {}", partId, datasheetUrl,
                    e.getMessage());
        }
    }

    private Part createPart(QuickAddRequest request) {
        Part part = new Part();
        part.setOrganisation(currentOrganisationService.current());
        part.setPartNumber(request.getPartNumber());
        part.setDescription(request.getDescription());
        part.setDetails(request.getDetails());
        part.setManufacturer(request.getManufacturer());
        part.setPersonalNumber(request.isPersonalNumber());
        part.setDatasheetUrl(request.getDatasheetUrl());
        part.setSpecs(specDefinitionService.canonicalizeKeys(request.getSpecs()));
        if (request.getCategoryId() != null) {
            Category category = categoryRepository
                    .findByIdAndOrganisationId(request.getCategoryId(), currentOrganisationService.currentId())
                    .orElseThrow(() -> new EntityNotFoundException("Category not found: " + request.getCategoryId()));
            part.setCategory(category);
        }
        if (request.getTags() != null) {
            part.getTags().addAll(tagService.resolveOrCreate(request.getTags()));
        }
        part.setCreatedBy(currentUserService.current());
        return partRepository.save(part);
    }
}
