package com.clele.parts.service;

import com.clele.parts.dto.LocationDTO;
import com.clele.parts.dto.LocationRequest;
import com.clele.parts.dto.LocationTreeDTO;
import com.clele.parts.model.AppUser;
import com.clele.parts.model.Location;
import com.clele.parts.model.StockEntry;
import com.clele.parts.repository.AppUserRepository;
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

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LocationService {

    private final LocationRepository locationRepository;
    private final AppUserRepository userRepository;
    private final StockEntryRepository stockEntryRepository;
    private final StockMovementRepository stockMovementRepository;
    private final CurrentUserService currentUserService;

    public List<LocationDTO> findAll() {
        return locationRepository.findAll().stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    /** Locations owned by the currently authenticated user (for stock-add pickers). */
    public List<LocationDTO> findMine() {
        AppUser me = currentUserService.current();
        return locationRepository.findByOwnerIdOrderByName(me.getId()).stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    /** Full location hierarchy as a nested tree (all owners), roots first. */
    public List<LocationTreeDTO> getTree() {
        return locationRepository.findByParentIsNull().stream()
                .map(this::toTreeDTO)
                .collect(Collectors.toList());
    }

    public LocationDTO findById(Long id) {
        return toDTO(getOrThrow(id));
    }

    @Transactional
    public LocationDTO create(LocationRequest request) {
        AppUser owner = currentUserService.current();
        Location parent = resolveParent(request.getParentId(), owner);
        if (locationRepository.existsSibling(owner.getId(), request.getName(),
                parent != null ? parent.getId() : null, null)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "You already have a location named \"" + request.getName() + "\" here");
        }
        Location location = Location.builder()
                .name(request.getName())
                .description(request.getDescription())
                .parent(parent)
                .owner(owner)
                .build();
        return toDTO(locationRepository.save(location));
    }

    @Transactional
    public LocationDTO update(Long id, LocationRequest request) {
        Location location = getOrThrow(id);
        requireManagePermission(location);

        // Optional reassignment to another user (admin only).
        AppUser targetOwner = location.getOwner();
        Long requestedOwnerId = request.getOwnerId();
        if (requestedOwnerId != null && !requestedOwnerId.equals(targetOwner.getId())) {
            if (!currentUserService.isAdmin()) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Only admins can reassign a location to another user");
            }
            // Children share their parent's owner; reassigning a parent would break that invariant.
            if (locationRepository.existsByParentId(id)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Cannot change the owner of a location that has sub-locations");
            }
            targetOwner = userRepository.findById(requestedOwnerId)
                    .orElseThrow(() -> new EntityNotFoundException("User not found: " + requestedOwnerId));
            location.setOwner(targetOwner);
        }

        Location parent = resolveParent(request.getParentId(), targetOwner);
        if (parent != null) {
            if (parent.getId().equals(id)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A location cannot be its own parent");
            }
            if (isDescendant(parent, id)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "A location cannot be moved under one of its own descendants");
            }
        }
        if (locationRepository.existsSibling(targetOwner.getId(), request.getName(),
                parent != null ? parent.getId() : null, id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "That owner already has a location named \"" + request.getName() + "\" here");
        }
        location.setParent(parent);
        location.setName(request.getName());
        location.setDescription(request.getDescription());
        return toDTO(locationRepository.save(location));
    }

    @Transactional
    public void delete(Long id) {
        Location location = getOrThrow(id);
        requireManagePermission(location);
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
     * then delete the source location. The source must be manageable by the current user (its owner
     * or an admin); the target may belong to any user.
     */
    @Transactional
    public void merge(Long sourceId, Long targetId) {
        if (sourceId.equals(targetId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Cannot merge a location into itself");
        }
        Location source = getOrThrow(sourceId);
        Location target = getOrThrow(targetId);
        requireManagePermission(source);
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
        stockEntryRepository.deleteByLocationId(sourceId);
        locationRepository.delete(source);
    }

    public long countAll() {
        return locationRepository.count();
    }

    /** Per-user roll-up of owned locations and the stock held in them (for the dashboard). */
    public List<com.clele.parts.dto.UserDashboardDTO> perUserStats() {
        return locationRepository.perUserStats();
    }

    private Location getOrThrow(Long id) {
        return locationRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Location not found: " + id));
    }

    /** Resolve and validate the requested parent: it must exist and be owned by {@code owner}. */
    private Location resolveParent(Long parentId, AppUser owner) {
        if (parentId == null) {
            return null;
        }
        Location parent = locationRepository.findById(parentId)
                .orElseThrow(() -> new EntityNotFoundException("Parent location not found: " + parentId));
        if (parent.getOwner() == null || !parent.getOwner().getId().equals(owner.getId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A location's parent must belong to the same owner");
        }
        return parent;
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
        AppUser owner = location.getOwner();
        List<LocationTreeDTO> childDTOs = location.getChildren().stream()
                .map(this::toTreeDTO)
                .collect(Collectors.toList());
        return LocationTreeDTO.builder()
                .id(location.getId())
                .name(location.getName())
                .description(location.getDescription())
                .parentId(location.getParent() != null ? location.getParent().getId() : null)
                .ownerId(owner != null ? owner.getId() : null)
                .ownerName(owner != null ? (owner.getFullName() != null ? owner.getFullName() : owner.getEmail()) : null)
                .children(childDTOs)
                .build();
    }

    /** A location may be managed by its owner or by an admin (USERS_EDIT). */
    private void requireManagePermission(Location location) {
        AppUser me = currentUserService.current();
        boolean owns = location.getOwner() != null && location.getOwner().getId().equals(me.getId());
        if (!owns && !currentUserService.isAdmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "You can only manage your own locations");
        }
    }

    private LocationDTO toDTO(Location location) {
        AppUser owner = location.getOwner();
        Location parent = location.getParent();
        return LocationDTO.builder()
                .id(location.getId())
                .name(location.getName())
                .description(location.getDescription())
                .parentId(parent != null ? parent.getId() : null)
                .parentName(parent != null ? parent.getName() : null)
                .breadcrumb(location.breadcrumb())
                .ownerId(owner != null ? owner.getId() : null)
                .ownerName(owner != null ? (owner.getFullName() != null ? owner.getFullName() : owner.getEmail()) : null)
                .build();
    }
}
