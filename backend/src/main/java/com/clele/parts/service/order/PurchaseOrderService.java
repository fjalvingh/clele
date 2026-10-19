package com.clele.parts.service.order;

import com.clele.parts.dto.*;
import com.clele.parts.model.*;
import com.clele.parts.repository.*;
import com.clele.parts.service.CurrentOrganisationService;
import com.clele.parts.service.CurrentUserService;
import com.clele.parts.service.StockMovementService;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Orders at suppliers, from the first draft to the last delivery.
 *
 * <p>A {@link PurchaseOrderStatus#DRAFT DRAFT} is where the iterating happens: order list items are
 * put on it (one line per part, quantities summed), the order file is uploaded to the supplier, and
 * the supplier's matched file comes back to {@link #replaceLines replace} the lines with what will
 * really be ordered. Placing the order fixes the lines; deliveries are then {@link #receive
 * received} into stock against them until nothing is outstanding.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PurchaseOrderService {

    private final PurchaseOrderRepository orderRepository;
    private final PurchaseOrderLineRepository lineRepository;
    private final OrderItemRepository itemRepository;
    private final PartSupplierRepository partSupplierRepository;
    private final PartRepository partRepository;
    private final LocationRepository locationRepository;
    private final StockEntryRepository stockEntryRepository;
    private final SupplierService supplierService;
    private final StockMovementService stockMovementService;
    private final CurrentOrganisationService currentOrganisationService;
    private final CurrentUserService currentUserService;

    public List<PurchaseOrderDTO> findAll() {
        Long orgId = currentOrganisationService.currentId();
        Map<Long, PurchaseOrderLineRepository.OrderTotals> totals =
                lineRepository.totalsByOrganisation(orgId).stream()
                        .collect(Collectors.toMap(PurchaseOrderLineRepository.OrderTotals::getOrderId,
                                Function.identity()));
        return orderRepository.findByOrganisationIdWithSupplier(orgId).stream()
                .map(order -> {
                    PurchaseOrderDTO dto = toSummaryDTO(order);
                    PurchaseOrderLineRepository.OrderTotals t = totals.get(order.getId());
                    dto.setLineCount(t == null ? 0 : t.getLineCount());
                    dto.setTotal(t == null ? BigDecimal.ZERO : t.getTotal());
                    return dto;
                })
                .toList();
    }

    public PurchaseOrderDTO findById(Long id) {
        return toDetailDTO(require(id));
    }

    PurchaseOrder require(Long id) {
        return orderRepository.findByIdAndOrganisationId(id, currentOrganisationService.currentId())
                .orElseThrow(() -> new EntityNotFoundException("Order not found: " + id));
    }

    PurchaseOrder requireStatus(Long id, PurchaseOrderStatus status, String action) {
        PurchaseOrder order = require(id);
        if (order.getStatus() != status) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Only " + (status == PurchaseOrderStatus.DRAFT ? "a draft" : "an ordered")
                            + " order can " + action + "; this one is " + order.getStatus().name().toLowerCase());
        }
        return order;
    }

    // ------------------------------------------------------------------
    // Draft: putting the order together
    // ------------------------------------------------------------------

    @Transactional
    public PurchaseOrderDTO create(PurchaseOrderRequest request) {
        if (request.getSupplierId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Supplier is required");
        }
        PurchaseOrder order = orderRepository.save(PurchaseOrder.builder()
                .organisation(currentOrganisationService.current())
                .supplier(supplierService.require(request.getSupplierId()))
                .status(PurchaseOrderStatus.DRAFT)
                .supplierReference(blankToNull(request.getSupplierReference()))
                .notes(blankToNull(request.getNotes()))
                .createdBy(currentUserService.current())
                .build());
        if (request.getItemIds() != null && !request.getItemIds().isEmpty()) {
            putItemsOnOrder(order, request.getItemIds());
        }
        return toDetailDTO(order);
    }

    /** The reference and notes can always change; the supplier only while nothing is placed. */
    @Transactional
    public PurchaseOrderDTO update(Long id, PurchaseOrderRequest request) {
        PurchaseOrder order = require(id);
        if (request.getSupplierId() != null && !request.getSupplierId().equals(order.getSupplier().getId())) {
            if (order.getStatus() != PurchaseOrderStatus.DRAFT) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "The supplier of a placed order cannot change");
            }
            order.setSupplier(supplierService.require(request.getSupplierId()));
        }
        order.setSupplierReference(blankToNull(request.getSupplierReference()));
        order.setNotes(blankToNull(request.getNotes()));
        return toDetailDTO(orderRepository.save(order));
    }

    @Transactional
    public PurchaseOrderDTO addItems(Long id, List<Long> itemIds) {
        PurchaseOrder order = requireStatus(id, PurchaseOrderStatus.DRAFT, "take more items");
        putItemsOnOrder(order, itemIds);
        return toDetailDTO(order);
    }

    /**
     * Links order list items to the order's line for their part, creating the line when the part
     * is not on the order yet and adding the item's quantity to it otherwise. Two items for the same
     * part — one for a project, one for stock — become one line at the supplier.
     */
    private void putItemsOnOrder(PurchaseOrder order, List<Long> itemIds) {
        List<OrderItem> items = itemRepository.findByIdInAndOrganisationId(
                itemIds, currentOrganisationService.currentId());
        if (items.size() != new HashSet<>(itemIds).size()) {
            throw new EntityNotFoundException("One or more order list items were not found");
        }
        for (OrderItem item : items) {
            if (item.getLine() != null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        item.getPart().getPartNumber() + " is already on order #"
                                + item.getLine().getOrder().getId());
            }
            PurchaseOrderLine line = lineRepository.findByOrderIdAndPartId(order.getId(), item.getPart().getId())
                    .orElseGet(() -> newLine(order, item.getPart()));
            line.setQuantity(line.getQuantity() + item.getQuantity());
            if (line.getUnitPrice() == null) {
                line.setUnitPrice(item.getUnitPrice());
            }
            lineRepository.save(line);
            item.setLine(line);
            itemRepository.save(item);
        }
    }

    /** A new, empty line, carrying what this supplier is known to call the part and charge for it. */
    private PurchaseOrderLine newLine(PurchaseOrder order, Part part) {
        Optional<PartSupplier> known = partSupplierRepository.findByPartIdAndSupplierId(
                part.getId(), order.getSupplier().getId());
        return lineRepository.save(PurchaseOrderLine.builder()
                .order(order)
                .part(part)
                .quantity(0)
                .unitPrice(known.map(PartSupplier::getLastUnitPrice).orElse(null))
                .supplierPartNumber(known.map(PartSupplier::getSupplierPartNumber).orElse(null))
                .build());
    }

    /**
     * Takes one item off a draft order and back onto the order list. Its quantity comes off the
     * line, and a line left with no items is removed entirely.
     */
    @Transactional
    public PurchaseOrderDTO removeItem(Long id, Long itemId) {
        PurchaseOrder order = requireStatus(id, PurchaseOrderStatus.DRAFT, "give items back");
        OrderItem item = itemRepository.findByIdAndOrganisationId(itemId, currentOrganisationService.currentId())
                .filter(i -> i.getLine() != null && i.getLine().getOrder().getId().equals(id))
                .orElseThrow(() -> new EntityNotFoundException("Item not on this order: " + itemId));
        PurchaseOrderLine line = item.getLine();
        item.setLine(null);
        itemRepository.save(item);
        if (itemRepository.findByLineId(line.getId()).isEmpty()) {
            lineRepository.delete(line);
        } else {
            line.setQuantity(Math.max(1, line.getQuantity() - item.getQuantity()));
            lineRepository.save(line);
        }
        return toDetailDTO(order);
    }

    @Transactional
    public PurchaseOrderDTO updateLine(Long id, Long lineId, PurchaseOrderLineRequest request) {
        PurchaseOrder order = requireStatus(id, PurchaseOrderStatus.DRAFT, "be changed");
        PurchaseOrderLine line = requireLine(order, lineId);
        line.setQuantity(request.getQuantity());
        line.setUnitPrice(request.getUnitPrice());
        line.setSupplierPartNumber(blankToNull(request.getSupplierPartNumber()));
        lineRepository.save(line);
        rememberAtSupplier(order.getSupplier(), line);
        return toDetailDTO(order);
    }

    /** Removes a line; the items it was made from go back on the order list. */
    @Transactional
    public PurchaseOrderDTO removeLine(Long id, Long lineId) {
        PurchaseOrder order = requireStatus(id, PurchaseOrderStatus.DRAFT, "be changed");
        dropLine(requireLine(order, lineId));
        return toDetailDTO(order);
    }

    /**
     * Replaces every line of a draft with the given set — what the supplier's file says will really
     * be ordered. Lines for parts not in the set are removed and their items go back on the order
     * list; the rest are created or overwritten. What the supplier told us is kept for next time:
     * its own part number and price in {@code part_supplier}, and the MPN and manufacturer on a
     * part that had none yet (a part known only by our own code gains its manufacturer's here).
     * A part that already has an MPN keeps it — the preview flags a difference, it never decides it.
     */
    @Transactional
    public PurchaseOrderDTO replaceLines(Long id, ReplaceOrderLinesRequest request) {
        PurchaseOrder order = requireStatus(id, PurchaseOrderStatus.DRAFT, "be changed");
        Long orgId = currentOrganisationService.currentId();
        Map<Long, PurchaseOrderLineRequest> wanted = mergeByPart(request.getLines());

        for (PurchaseOrderLine line : lineRepository.findByOrderIdWithPart(order.getId())) {
            if (!wanted.containsKey(line.getPart().getId())) {
                dropLine(line);
            }
        }
        lineRepository.flush();

        for (PurchaseOrderLineRequest wish : wanted.values()) {
            Part part = partRepository.findByIdAndOrganisationId(wish.getPartId(), orgId)
                    .orElseThrow(() -> new EntityNotFoundException("Part not found: " + wish.getPartId()));
            PurchaseOrderLine line = lineRepository.findByOrderIdAndPartId(order.getId(), part.getId())
                    .orElseGet(() -> newLine(order, part));
            line.setQuantity(wish.getQuantity());
            if (wish.getUnitPrice() != null) {
                line.setUnitPrice(wish.getUnitPrice());
            }
            if (wish.getSupplierPartNumber() != null && !wish.getSupplierPartNumber().isBlank()) {
                line.setSupplierPartNumber(wish.getSupplierPartNumber().trim());
            }
            lineRepository.save(line);

            boolean partChanged = false;
            if (isBlank(part.getMpn()) && !isBlank(wish.getMpn())) {
                part.setMpn(wish.getMpn().trim());
                partChanged = true;
            }
            if (isBlank(part.getManufacturer()) && !isBlank(wish.getManufacturer())) {
                part.setManufacturer(wish.getManufacturer().trim());
                partChanged = true;
            }
            if (partChanged) {
                partRepository.save(part);
            }
            rememberAtSupplier(order.getSupplier(), line);

            // The items now cost what the supplier charges, so the list's history adds up.
            if (line.getUnitPrice() != null) {
                for (OrderItem item : itemRepository.findByLineId(line.getId())) {
                    item.setUnitPrice(line.getUnitPrice());
                    itemRepository.save(item);
                }
            }
        }
        return toDetailDTO(order);
    }

    /**
     * Several rows of a supplier's file can name one part (split packaging, a line entered twice);
     * an order has one line per part, so they are summed, at the quantity-weighted price.
     */
    private Map<Long, PurchaseOrderLineRequest> mergeByPart(List<PurchaseOrderLineRequest> lines) {
        Map<Long, PurchaseOrderLineRequest> merged = new LinkedHashMap<>();
        for (PurchaseOrderLineRequest line : lines) {
            PurchaseOrderLineRequest seen = merged.get(line.getPartId());
            if (seen == null) {
                merged.put(line.getPartId(), new PurchaseOrderLineRequest(line.getPartId(), line.getQuantity(),
                        line.getUnitPrice(), line.getSupplierPartNumber(), line.getMpn(), line.getManufacturer()));
                continue;
            }
            int quantity = seen.getQuantity() + line.getQuantity();
            if (seen.getUnitPrice() != null && line.getUnitPrice() != null) {
                seen.setUnitPrice(seen.getUnitPrice().multiply(BigDecimal.valueOf(seen.getQuantity()))
                        .add(line.getUnitPrice().multiply(BigDecimal.valueOf(line.getQuantity())))
                        .divide(BigDecimal.valueOf(quantity), 5, RoundingMode.HALF_UP));
            } else if (seen.getUnitPrice() == null) {
                seen.setUnitPrice(line.getUnitPrice());
            }
            seen.setQuantity(quantity);
            if (isBlank(seen.getSupplierPartNumber())) {
                seen.setSupplierPartNumber(line.getSupplierPartNumber());
            }
            if (isBlank(seen.getMpn())) {
                seen.setMpn(line.getMpn());
            }
            if (isBlank(seen.getManufacturer())) {
                seen.setManufacturer(line.getManufacturer());
            }
        }
        return merged;
    }

    private void dropLine(PurchaseOrderLine line) {
        for (OrderItem item : itemRepository.findByLineId(line.getId())) {
            item.setLine(null);
            itemRepository.save(item);
        }
        lineRepository.delete(line);
    }

    /** Records what this supplier calls the part and charges for it, for the next order. */
    private void rememberAtSupplier(Supplier supplier, PurchaseOrderLine line) {
        if (line.getSupplierPartNumber() == null && line.getUnitPrice() == null) {
            return;
        }
        PartSupplier known = partSupplierRepository.findByPartIdAndSupplierId(line.getPart().getId(), supplier.getId())
                .orElseGet(() -> PartSupplier.builder().part(line.getPart()).supplier(supplier).build());
        if (line.getSupplierPartNumber() != null) {
            known.setSupplierPartNumber(line.getSupplierPartNumber());
        }
        if (line.getUnitPrice() != null) {
            known.setLastUnitPrice(line.getUnitPrice());
        }
        partSupplierRepository.save(known);
    }

    // ------------------------------------------------------------------
    // Phase transitions
    // ------------------------------------------------------------------

    /** The order has been placed with the supplier: its lines are fixed from here on. */
    @Transactional
    public PurchaseOrderDTO place(Long id, PurchaseOrderRequest request) {
        PurchaseOrder order = requireStatus(id, PurchaseOrderStatus.DRAFT, "be placed");
        if (lineRepository.findByOrderIdWithPart(id).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The order has no lines");
        }
        if (request != null && !isBlank(request.getSupplierReference())) {
            order.setSupplierReference(request.getSupplierReference().trim());
        }
        order.setStatus(PurchaseOrderStatus.ORDERED);
        order.setOrderedAt(LocalDateTime.now());
        return toDetailDTO(orderRepository.save(order));
    }

    /** Undoes placing, for an order placed by mistake — as long as nothing has come in on it. */
    @Transactional
    public PurchaseOrderDTO reopen(Long id) {
        PurchaseOrder order = requireStatus(id, PurchaseOrderStatus.ORDERED, "go back to draft");
        requireNothingReceived(order, "go back to draft");
        order.setStatus(PurchaseOrderStatus.DRAFT);
        order.setOrderedAt(null);
        return toDetailDTO(orderRepository.save(order));
    }

    /**
     * Books parts that came in into stock, each line at the location given, as a PURCHASE at the
     * line's price that names the order. Each line is acknowledged on its own — a delivery slip is
     * the same request for several lines at once. When nothing is outstanding any more the order
     * is received.
     */
    @Transactional
    public PurchaseOrderDTO receive(Long id, ReceiveOrderRequest request) {
        PurchaseOrder order = requireStatus(id, PurchaseOrderStatus.ORDERED, "receive parts");
        Long orgId = currentOrganisationService.currentId();
        Location lastLocation = null;
        for (ReceiveOrderRequest.Receipt receipt : request.getReceipts()) {
            PurchaseOrderLine line = requireLine(order, receipt.getLineId());
            if (receipt.getQuantity() > line.outstanding()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        line.getPart().getPartNumber() + ": only " + line.outstanding() + " still to come");
            }
            Location location = locationRepository.findByIdAndOrganisationId(receipt.getLocationId(), orgId)
                    .orElseThrow(() -> new EntityNotFoundException("Location not found: " + receipt.getLocationId()));
            stockMovementService.applyForOrder(line.getPart(), location, receipt.getQuantity(),
                    line.getUnitPrice(), describe(order), order);
            line.setQtyReceived(line.getQtyReceived() + receipt.getQuantity());
            lineRepository.save(line);
            lastLocation = location;
        }
        if (lastLocation != null) {
            currentUserService.rememberLastLocation(lastLocation);
        }
        if (lineRepository.findByOrderIdWithPart(id).stream().allMatch(l -> l.outstanding() == 0)) {
            order.setStatus(PurchaseOrderStatus.RECEIVED);
            order.setReceivedAt(LocalDateTime.now());
            orderRepository.save(order);
        }
        return toDetailDTO(order);
    }

    /**
     * Closes an order that will not be delivered in full. What never came goes back on the order
     * list as new items, so it is not forgotten — for the project its line was ordered for, when
     * all of the line's items shared one.
     */
    @Transactional
    public PurchaseOrderDTO close(Long id) {
        PurchaseOrder order = requireStatus(id, PurchaseOrderStatus.ORDERED, "be closed");
        AppUser me = currentUserService.current();
        for (PurchaseOrderLine line : lineRepository.findByOrderIdWithPart(id)) {
            if (line.outstanding() == 0) {
                continue;
            }
            Set<Project> projects = itemRepository.findByLineId(line.getId()).stream()
                    .map(OrderItem::getProject)
                    .collect(Collectors.toCollection(HashSet::new));
            itemRepository.save(OrderItem.builder()
                    .organisation(order.getOrganisation())
                    .part(line.getPart())
                    .quantity(line.outstanding())
                    .unitPrice(line.getUnitPrice())
                    .project(projects.size() == 1 ? projects.iterator().next() : null)
                    .notes("Not delivered on order #" + order.getId())
                    .createdBy(me)
                    .build());
        }
        order.setStatus(PurchaseOrderStatus.RECEIVED);
        order.setReceivedAt(LocalDateTime.now());
        return toDetailDTO(orderRepository.save(order));
    }

    /**
     * Abandons an order nothing has come in on. Its items go back on the order list; its lines stay
     * as the record of what was going to be ordered.
     */
    @Transactional
    public PurchaseOrderDTO cancel(Long id) {
        PurchaseOrder order = require(id);
        if (order.getStatus() != PurchaseOrderStatus.DRAFT && order.getStatus() != PurchaseOrderStatus.ORDERED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The order is already "
                    + order.getStatus().name().toLowerCase());
        }
        requireNothingReceived(order, "be cancelled");
        for (PurchaseOrderLine line : lineRepository.findByOrderIdWithPart(id)) {
            for (OrderItem item : itemRepository.findByLineId(line.getId())) {
                item.setLine(null);
                itemRepository.save(item);
            }
        }
        order.setStatus(PurchaseOrderStatus.CANCELLED);
        return toDetailDTO(orderRepository.save(order));
    }

    /** Deletes a draft (its items back on the list) or a cancelled order, lines and all. */
    @Transactional
    public void delete(Long id) {
        PurchaseOrder order = require(id);
        if (order.getStatus() != PurchaseOrderStatus.DRAFT && order.getStatus() != PurchaseOrderStatus.CANCELLED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Only a draft or cancelled order can be deleted");
        }
        lineRepository.findByOrderIdWithPart(id).forEach(this::dropLine);
        orderRepository.delete(order);
    }

    private void requireNothingReceived(PurchaseOrder order, String action) {
        if (lineRepository.findByOrderIdWithPart(order.getId()).stream().anyMatch(l -> l.getQtyReceived() > 0)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Parts have already been received on this order; it cannot " + action);
        }
    }

    PurchaseOrderLine requireLine(PurchaseOrder order, Long lineId) {
        return lineRepository.findById(lineId)
                .filter(l -> l.getOrder().getId().equals(order.getId()))
                .orElseThrow(() -> new EntityNotFoundException("Line not on this order: " + lineId));
    }

    /** The stock movement comment: "Order #12 at Mouser (ref 123456)". */
    private static String describe(PurchaseOrder order) {
        return "Order #" + order.getId() + " at " + order.getSupplier().getName()
                + (order.getSupplierReference() == null ? "" : " (ref " + order.getSupplierReference() + ")");
    }

    // ------------------------------------------------------------------
    // Mapping
    // ------------------------------------------------------------------

    private PurchaseOrderDTO toSummaryDTO(PurchaseOrder order) {
        AppUser by = order.getCreatedBy();
        return PurchaseOrderDTO.builder()
                .id(order.getId())
                .supplierId(order.getSupplier().getId())
                .supplierName(order.getSupplier().getName())
                .status(order.getStatus())
                .supplierReference(order.getSupplierReference())
                .notes(order.getNotes())
                .createdBy(by == null ? null : (by.getFullName() != null ? by.getFullName() : by.getEmail()))
                .createdAt(order.getCreatedAt())
                .orderedAt(order.getOrderedAt())
                .receivedAt(order.getReceivedAt())
                .build();
    }

    PurchaseOrderDTO toDetailDTO(PurchaseOrder order) {
        List<PurchaseOrderLine> lines = lineRepository.findByOrderIdWithPart(order.getId());
        Map<Long, List<OrderItem>> itemsByLine = lines.isEmpty()
                ? Map.of()
                : itemRepository.findByLineIdIn(lines.stream().map(PurchaseOrderLine::getId).toList()).stream()
                        .collect(Collectors.groupingBy(i -> i.getLine().getId()));
        Map<Long, Long> fullestLocation = fullestLocationByPart(lines);

        List<PurchaseOrderLineDTO> lineDTOs = lines.stream()
                .map(l -> toLineDTO(l, itemsByLine.getOrDefault(l.getId(), List.of()),
                        fullestLocation.get(l.getPart().getId())))
                .toList();
        PurchaseOrderDTO dto = toSummaryDTO(order);
        dto.setLines(lineDTOs);
        dto.setLineCount(lineDTOs.size());
        dto.setTotal(lineDTOs.stream()
                .map(PurchaseOrderLineDTO::getTotalPrice)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        return dto;
    }

    PurchaseOrderLineDTO toLineDTO(PurchaseOrderLine line, List<OrderItem> items, Long suggestedLocationId) {
        Part part = line.getPart();
        return PurchaseOrderLineDTO.builder()
                .id(line.getId())
                .partId(part.getId())
                .partNumber(part.getPartNumber())
                .description(part.getDescription())
                .manufacturer(part.getManufacturer())
                .mpn(part.getMpn())
                .supplierPartNumber(line.getSupplierPartNumber())
                .quantity(line.getQuantity())
                .requested(items.stream().mapToInt(OrderItem::getQuantity).sum())
                .unitPrice(line.getUnitPrice())
                .totalPrice(line.getUnitPrice() == null
                        ? null
                        : line.getUnitPrice().multiply(BigDecimal.valueOf(line.getQuantity())))
                .qtyReceived(line.getQtyReceived())
                .outstanding(line.outstanding())
                .suggestedLocationId(suggestedLocationId)
                .items(items.stream().map(OrderItemService::toDTO).toList())
                .build();
    }

    /** Per part, the location already holding most of it — where a delivery most likely goes. */
    private Map<Long, Long> fullestLocationByPart(List<PurchaseOrderLine> lines) {
        if (lines.isEmpty()) {
            return Map.of();
        }
        Map<Long, StockEntry> fullest = new HashMap<>();
        for (StockEntry entry : stockEntryRepository.findByPartIdInAndOrganisationId(
                lines.stream().map(l -> l.getPart().getId()).toList(), currentOrganisationService.currentId())) {
            fullest.merge(entry.getPart().getId(), entry,
                    (a, b) -> b.getQuantity() > a.getQuantity() ? b : a);
        }
        Map<Long, Long> result = new HashMap<>();
        fullest.forEach((partId, entry) -> result.put(partId, entry.getLocation().getId()));
        return result;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String blankToNull(String value) {
        return isBlank(value) ? null : value.trim();
    }
}
