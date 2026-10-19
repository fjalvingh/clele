package com.clele.parts.service.order;

import com.clele.parts.dto.OrderItemDTO;
import com.clele.parts.dto.OrderItemRequest;
import com.clele.parts.model.*;
import com.clele.parts.repository.OrderItemRepository;
import com.clele.parts.repository.PartRepository;
import com.clele.parts.repository.PartSupplierRepository;
import com.clele.parts.repository.ProjectRepository;
import com.clele.parts.service.CurrentOrganisationService;
import com.clele.parts.service.CurrentUserService;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;

/**
 * The <b>order list</b>: what the organisation wants to buy, before it is on an order. Shared by
 * every member, like locations — anyone may add to it and anyone may order from it.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrderItemService {

    private final OrderItemRepository orderItemRepository;
    private final PartRepository partRepository;
    private final PartSupplierRepository partSupplierRepository;
    private final ProjectRepository projectRepository;
    private final CurrentOrganisationService currentOrganisationService;
    private final CurrentUserService currentUserService;

    public List<OrderItemDTO> findOpen() {
        return orderItemRepository.findOpenByOrganisationId(currentOrganisationService.currentId())
                .stream().map(OrderItemService::toDTO).toList();
    }

    /**
     * Puts a part on the list. Without a price the last one any supplier charged is taken, so the
     * list total means something from the start; an item for a part never bought stays unpriced.
     */
    @Transactional
    public OrderItemDTO create(OrderItemRequest request) {
        if (request.getPartId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Part is required");
        }
        Long orgId = currentOrganisationService.currentId();
        Part part = partRepository.findByIdAndOrganisationId(request.getPartId(), orgId)
                .orElseThrow(() -> new EntityNotFoundException("Part not found: " + request.getPartId()));
        BigDecimal price = request.getUnitPrice() != null
                ? request.getUnitPrice()
                : partSupplierRepository.findFirstByPartIdAndLastUnitPriceIsNotNullOrderByUpdatedAtDesc(part.getId())
                        .map(PartSupplier::getLastUnitPrice)
                        .orElse(null);
        OrderItem item = OrderItem.builder()
                .organisation(currentOrganisationService.current())
                .part(part)
                .quantity(request.getQuantity())
                .unitPrice(price)
                .project(resolveProject(request.getProjectId()))
                .notes(blankToNull(request.getNotes()))
                .createdBy(currentUserService.current())
                .build();
        return toDTO(orderItemRepository.save(item));
    }

    /** Changes an item still on the list. Once it is on an order the order's line is what counts. */
    @Transactional
    public OrderItemDTO update(Long id, OrderItemRequest request) {
        OrderItem item = requireOpen(id);
        item.setQuantity(request.getQuantity());
        item.setUnitPrice(request.getUnitPrice());
        item.setProject(resolveProject(request.getProjectId()));
        item.setNotes(blankToNull(request.getNotes()));
        return toDTO(orderItemRepository.save(item));
    }

    @Transactional
    public void delete(Long id) {
        orderItemRepository.delete(requireOpen(id));
    }

    OrderItem requireOpen(Long id) {
        OrderItem item = orderItemRepository.findByIdAndOrganisationId(id, currentOrganisationService.currentId())
                .orElseThrow(() -> new EntityNotFoundException("Order list item not found: " + id));
        if (item.getLine() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "That item is already on order #" + item.getLine().getOrder().getId());
        }
        return item;
    }

    /** Any live project of the organisation — the list is shared, so not only the caller's own. */
    private Project resolveProject(Long projectId) {
        if (projectId == null) {
            return null;
        }
        return projectRepository.findByIdAndOrganisationIdAndDeletedFalse(
                        projectId, currentOrganisationService.currentId())
                .orElseThrow(() -> new EntityNotFoundException("Project not found: " + projectId));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    static OrderItemDTO toDTO(OrderItem item) {
        Part part = item.getPart();
        PurchaseOrder order = item.getLine() == null ? null : item.getLine().getOrder();
        return OrderItemDTO.builder()
                .id(item.getId())
                .partId(part.getId())
                .partNumber(part.getPartNumber())
                .description(part.getDescription())
                .manufacturer(part.getManufacturer())
                .mpn(part.getMpn())
                .quantity(item.getQuantity())
                .unitPrice(item.getUnitPrice())
                .totalPrice(item.getUnitPrice() == null
                        ? null
                        : item.getUnitPrice().multiply(BigDecimal.valueOf(item.getQuantity())))
                .projectId(item.getProject() == null ? null : item.getProject().getId())
                .projectName(item.getProject() == null ? null : item.getProject().getName())
                .orderId(order == null ? null : order.getId())
                .orderStatus(order == null ? null : order.getStatus())
                .notes(item.getNotes())
                .createdAt(item.getCreatedAt())
                .build();
    }
}
