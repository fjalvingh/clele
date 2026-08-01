package com.clele.parts.service;

import com.clele.parts.dto.LocationDTO;
import com.clele.parts.dto.LocationDashboardDTO;
import com.clele.parts.dto.LocationStatsDTO;
import com.clele.parts.dto.LocationRequest;
import com.clele.parts.dto.LocationTreeDTO;
import com.clele.parts.model.Location;
import com.clele.parts.model.Organisation;
import com.clele.parts.model.StockEntry;
import com.clele.parts.repository.LocationRepository;
import com.clele.parts.repository.StockEntryRepository;
import com.clele.parts.repository.StockMovementRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Locations belong to the organisation, not to a user (V36): every member of an organisation sees
 * and uses the same locations, and no location is visible from another organisation.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LocationService {

    private final LocationRepository locationRepository;
    private final StockEntryRepository stockEntryRepository;
    private final StockMovementRepository stockMovementRepository;
    private final CurrentOrganisationService currentOrganisationService;

    public List<LocationDTO> findAll() {
        return locationRepository.findByOrganisationIdOrderByName(currentOrganisationService.currentId())
                .stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    /**
     * Locations available for stock pickers. Same set as {@link #findAll()} now that locations are
     * shared across the organisation; kept as its own endpoint so callers need not change.
     */
    public List<LocationDTO> findMine() {
        return findAll();
    }

    /** Full location hierarchy of the current organisation as a nested tree, roots first. */
    public List<LocationTreeDTO> getTree() {
        return locationRepository
                .findByOrganisationIdAndParentIsNull(currentOrganisationService.currentId()).stream()
                .map(this::toTreeDTO)
                .collect(Collectors.toList());
    }

    public LocationDTO findById(Long id) {
        return toDTO(getOrThrow(id));
    }

    @Transactional
    public LocationDTO create(LocationRequest request) {
        Organisation organisation = currentOrganisationService.current();
        Location parent = resolveParent(request.getParentId(), organisation);
        if (locationRepository.existsSibling(organisation.getId(), request.getName(),
                parent != null ? parent.getId() : null, null)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "There is already a location named \"" + request.getName() + "\" here");
        }
        Location location = Location.builder()
                .name(request.getName())
                .description(request.getDescription())
                .parent(parent)
                .organisation(organisation)
                .build();
        return toDTO(locationRepository.save(location));
    }

    @Transactional
    public LocationDTO update(Long id, LocationRequest request) {
        Location location = getOrThrow(id);
        Organisation organisation = currentOrganisationService.current();

        Location parent = resolveParent(request.getParentId(), organisation);
        if (parent != null) {
            if (parent.getId().equals(id)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A location cannot be its own parent");
            }
            if (isDescendant(parent, id)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "A location cannot be moved under one of its own descendants");
            }
        }
        if (locationRepository.existsSibling(organisation.getId(), request.getName(),
                parent != null ? parent.getId() : null, id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "There is already a location named \"" + request.getName() + "\" here");
        }
        location.setParent(parent);
        location.setName(request.getName());
        location.setDescription(request.getDescription());
        return toDTO(locationRepository.save(location));
    }

    @Transactional
    public void delete(Long id) {
        Location location = getOrThrow(id);
        if (locationRepository.existsByParentId(id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Cannot delete a location that has sub-locations. Delete or move them first.");
        }
        // A user's last-used pointer (app_user.last_location_id) is cleared automatically on
        // delete (ON DELETE SET NULL), so it does not block deletion.
        if (stockEntryRepository.existsByLocationId(id) || stockMovementRepository.existsByLocationId(id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This location has stock or stock history and cannot be deleted");
        }
        locationRepository.delete(location);
    }

    /**
     * Merge {@code sourceId} into {@code targetId}: fold the source location's on-hand stock into the
     * target and re-point its entire ledger to the target (preserving the full movement history),
     * then delete the source location. Both must be in the current organisation.
     */
    @Transactional
    public void merge(Long sourceId, Long targetId) {
        if (sourceId.equals(targetId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Cannot merge a location into itself");
        }
        Location source = getOrThrow(sourceId);
        Location target = getOrThrow(targetId);
        // Children would be orphaned by deleting their parent — merge/move them first.
        if (locationRepository.existsByParentId(sourceId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Cannot merge a location that has sub-locations. Merge or move them first.");
        }
        // Fold each part's on-hand aggregate into the target (find-or-create, carrying price). The
        // ledger is preserved by re-pointing below, so the aggregate is adjusted directly here
        // rather than by writing new movements (which would double-count the re-pointed history).
        for (StockEntry src : stockEntryRepository.findByLocationId(sourceId)) {
            StockEntry tgt = stockEntryRepository
                    .findByPartIdAndLocationId(src.getPart().getId(), targetId)
                    .orElseGet(() -> StockEntry.builder()
                            .part(src.getPart())
                            .location(target)
                            .quantity(0)
                            .build());
            tgt.setQuantity(tgt.getQuantity() + src.getQuantity());
            if (src.getUnitPrice() != null) {
                tgt.setUnitPrice(src.getUnitPrice());
            }
            stockEntryRepository.save(tgt);
        }
        // Preserve history: re-point the source's ledger to the target so every movement (with its
        // original type, price, date and author) lives on under the target location. This keeps the
        // invariant Σ(target movements) == target on-hand for each part. The FK to location has no
        // cascade, so re-pointing also frees the source for deletion. Drop the now-empty source
        // aggregates, then delete the source location.
        stockMovementRepository.repointLocation(target, sourceId);
        stockMovementRepository.repointTargetLocation(target, sourceId);
        stockEntryRepository.deleteByLocationId(sourceId);
        locationRepository.delete(source);
    }

    public long countAll() {
        return locationRepository.countByOrganisationId(currentOrganisationService.currentId());
    }

    /** Per-root-location roll-up of the stock in this organisation (for the dashboard). */
    public List<LocationDashboardDTO> perLocationStats() {
        return locationRepository.perLocationStats(currentOrganisationService.currentId()).stream()
                .map(LocationRepository::toDTO)
                .collect(Collectors.toList());
    }

    /** Stock roll-up for every location in this organisation (for the Locations tree). */
    public List<LocationStatsDTO> locationStats() {
        return locationRepository.locationStats(currentOrganisationService.currentId()).stream()
                .map(LocationRepository::toStatsDTO)
                .collect(Collectors.toList());
    }

    /**
     * Load a location, refusing anything outside the current organisation. Reported as "not found"
     * rather than "forbidden": another organisation's locations do not exist as far as this
     * organisation is concerned.
     */
    Location getOrThrow(Long id) {
        return locationRepository.findByIdAndOrganisationId(id, currentOrganisationService.currentId())
                .orElseThrow(() -> new EntityNotFoundException("Location not found: " + id));
    }

    /** Resolve and validate the requested parent: it must exist in the same organisation. */
    private Location resolveParent(Long parentId, Organisation organisation) {
        if (parentId == null) {
            return null;
        }
        return locationRepository.findByIdAndOrganisationId(parentId, organisation.getId())
                .orElseThrow(() -> new EntityNotFoundException("Parent location not found: " + parentId));
    }

    /** True if {@code ancestorId} appears anywhere on the parent chain above {@code node}. */
    private boolean isDescendant(Location node, Long ancestorId) {
        Location current = node.getParent();
        while (current != null) {
            if (current.getId().equals(ancestorId)) {
                return true;
            }
            current = current.getParent();
        }
        return false;
    }

    private LocationTreeDTO toTreeDTO(Location location) {
        List<LocationTreeDTO> childDTOs = location.getChildren().stream()
                .map(this::toTreeDTO)
                .collect(Collectors.toList());
        return LocationTreeDTO.builder()
                .id(location.getId())
                .name(location.getName())
                .description(location.getDescription())
                .parentId(location.getParent() != null ? location.getParent().getId() : null)
                .children(childDTOs)
                .build();
    }

    private LocationDTO toDTO(Location location) {
        Location parent = location.getParent();
        return LocationDTO.builder()
                .id(location.getId())
                .name(location.getName())
                .description(location.getDescription())
                .parentId(parent != null ? parent.getId() : null)
                .parentName(parent != null ? parent.getName() : null)
                .breadcrumb(location.breadcrumb())
                .build();
    }
}
