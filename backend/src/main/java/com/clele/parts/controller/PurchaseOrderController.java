package com.clele.parts.controller;

import com.clele.parts.dto.*;
import com.clele.parts.model.Permissions;
import com.clele.parts.service.order.OrderFileColumnMapper.Kind;
import com.clele.parts.service.order.OrderFileService;
import com.clele.parts.service.order.PurchaseOrderService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Orders at suppliers. A draft takes order list items and the supplier's matched file; a placed
 * order is received into stock line by line or from a delivery slip.
 */
@RestController
@RequestMapping("/api/purchase-orders")
@RequiredArgsConstructor
@Tag(name = "Orders", description = "Orders at suppliers, from draft to delivery")
@PreAuthorize("hasAuthority('" + Permissions.PARTS_EDIT + "')")
public class PurchaseOrderController {

    private final PurchaseOrderService orderService;
    private final OrderFileService fileService;
    private final ObjectMapper objectMapper;

    @GetMapping
    @Operation(summary = "List the organisation's orders, newest first")
    public List<PurchaseOrderDTO> list() {
        return orderService.findAll();
    }

    @PostMapping
    @Operation(summary = "Start a draft order at a supplier, optionally with order list items on it")
    public ResponseEntity<PurchaseOrderDTO> create(@RequestBody PurchaseOrderRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(orderService.create(request));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get an order with its lines and the items each line was made from")
    public PurchaseOrderDTO get(@PathVariable Long id) {
        return orderService.findById(id);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Change an order's supplier reference and notes (and supplier, while a draft)")
    public PurchaseOrderDTO update(@PathVariable Long id, @RequestBody PurchaseOrderRequest request) {
        return orderService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a draft or cancelled order; a draft's items go back on the order list")
    public void delete(@PathVariable Long id) {
        orderService.delete(id);
    }

    // ------------------------------------------------------------------
    // Draft
    // ------------------------------------------------------------------

    @PostMapping("/{id}/items")
    @Operation(summary = "Put order list items on a draft order")
    public PurchaseOrderDTO addItems(@PathVariable Long id, @Valid @RequestBody OrderItemIdsRequest request) {
        return orderService.addItems(id, request.getItemIds());
    }

    @DeleteMapping("/{id}/items/{itemId}")
    @Operation(summary = "Take one item off a draft order, back onto the order list")
    public PurchaseOrderDTO removeItem(@PathVariable Long id, @PathVariable Long itemId) {
        return orderService.removeItem(id, itemId);
    }

    @PutMapping("/{id}/lines/{lineId}")
    @Operation(summary = "Change a draft line's quantity, price or supplier part number")
    public PurchaseOrderDTO updateLine(@PathVariable Long id, @PathVariable Long lineId,
                                       @Valid @RequestBody PurchaseOrderLineRequest request) {
        return orderService.updateLine(id, lineId, request);
    }

    @DeleteMapping("/{id}/lines/{lineId}")
    @Operation(summary = "Remove a draft line; its items go back on the order list")
    public PurchaseOrderDTO removeLine(@PathVariable Long id, @PathVariable Long lineId) {
        return orderService.removeLine(id, lineId);
    }

    @PutMapping("/{id}/lines")
    @Operation(summary = "Replace every line of a draft (what the supplier's file said)")
    public PurchaseOrderDTO replaceLines(@PathVariable Long id, @Valid @RequestBody ReplaceOrderLinesRequest request) {
        return orderService.replaceLines(id, request);
    }

    @GetMapping("/{id}/export")
    @Operation(summary = "Download the order as an .xlsx to upload at the supplier")
    public ResponseEntity<byte[]> export(@PathVariable Long id) {
        OrderFileService.ExportedFile file = fileService.export(id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(file.filename()).build().toString())
                .body(file.data());
    }

    @PostMapping(path = "/{id}/supplier-file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Read the supplier's matched order file against a draft (stores nothing)")
    public OrderFilePreviewDTO previewSupplierFile(@PathVariable Long id,
                                                   @RequestParam("file") MultipartFile file,
                                                   @RequestParam(value = "mapping", required = false) String mapping) {
        return fileService.preview(id, bytes(file), Kind.ORDER, parseMapping(mapping));
    }

    @PostMapping(path = "/{id}/delivery-file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Read a delivery slip against a placed order (stores nothing)")
    public OrderFilePreviewDTO previewDeliveryFile(@PathVariable Long id,
                                                   @RequestParam("file") MultipartFile file,
                                                   @RequestParam(value = "mapping", required = false) String mapping) {
        return fileService.preview(id, bytes(file), Kind.DELIVERY, parseMapping(mapping));
    }

    // ------------------------------------------------------------------
    // Phase transitions
    // ------------------------------------------------------------------

    @PostMapping("/{id}/place")
    @Operation(summary = "Mark a draft as placed with the supplier, optionally with their order number")
    public PurchaseOrderDTO place(@PathVariable Long id, @RequestBody(required = false) PurchaseOrderRequest request) {
        return orderService.place(id, request);
    }

    @PostMapping("/{id}/reopen")
    @Operation(summary = "Put a placed order back to draft (nothing received yet)")
    public PurchaseOrderDTO reopen(@PathVariable Long id) {
        return orderService.reopen(id);
    }

    @PostMapping("/{id}/receive")
    @Operation(summary = "Book received parts into stock, per line and location")
    public PurchaseOrderDTO receive(@PathVariable Long id, @Valid @RequestBody ReceiveOrderRequest request) {
        return orderService.receive(id, request);
    }

    @PostMapping("/{id}/close")
    @Operation(summary = "Close a placed order; what never came goes back on the order list")
    public PurchaseOrderDTO close(@PathVariable Long id) {
        return orderService.close(id);
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Cancel an order nothing was received on; its items go back on the order list")
    public PurchaseOrderDTO cancel(@PathVariable Long id) {
        return orderService.cancel(id);
    }

    private static byte[] bytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read the upload");
        }
    }

    /** Multipart, so the column mapping rides along as a JSON string — as in the BOM import. */
    private Map<String, String> parseMapping(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, String>>() {
            });
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Could not read the column mapping: " + e.getMessage());
        }
    }
}
