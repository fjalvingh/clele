package com.clele.parts.controller;

import com.clele.parts.dto.OrderItemDTO;
import com.clele.parts.dto.OrderItemRequest;
import com.clele.parts.model.Permissions;
import com.clele.parts.service.order.OrderItemService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** The order list: parts to buy that are not on an order yet. */
@RestController
@RequestMapping("/api/order-items")
@RequiredArgsConstructor
@Tag(name = "Order list", description = "Parts to order, before they are on an order")
@PreAuthorize("hasAuthority('" + Permissions.PARTS_EDIT + "')")
public class OrderItemController {

    private final OrderItemService orderItemService;

    @GetMapping
    @Operation(summary = "The order list: every item not yet on an order")
    public List<OrderItemDTO> list() {
        return orderItemService.findOpen();
    }

    @PostMapping
    @Operation(summary = "Put a part on the order list")
    public ResponseEntity<OrderItemDTO> create(@Valid @RequestBody OrderItemRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(orderItemService.create(request));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Change an order list item (quantity, price, project, notes)")
    public OrderItemDTO update(@PathVariable Long id, @Valid @RequestBody OrderItemRequest request) {
        return orderItemService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Take an item off the order list")
    public void delete(@PathVariable Long id) {
        orderItemService.delete(id);
    }
}
