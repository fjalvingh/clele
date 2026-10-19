package com.clele.parts.service.order;

import com.clele.parts.dto.SupplierDTO;
import com.clele.parts.dto.SupplierRequest;
import com.clele.parts.model.Supplier;
import com.clele.parts.repository.PartSupplierRepository;
import com.clele.parts.repository.PurchaseOrderRepository;
import com.clele.parts.repository.SupplierRepository;
import com.clele.parts.service.CurrentOrganisationService;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/** The organisation's list of distributors. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SupplierService {

    private final SupplierRepository supplierRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final PartSupplierRepository partSupplierRepository;
    private final CurrentOrganisationService currentOrganisationService;

    public List<SupplierDTO> findAll() {
        return supplierRepository.findByOrganisationIdOrderByNameAsc(currentOrganisationService.currentId())
                .stream().map(SupplierService::toDTO).toList();
    }

    public Supplier require(Long id) {
        return supplierRepository.findByIdAndOrganisationId(id, currentOrganisationService.currentId())
                .orElseThrow(() -> new EntityNotFoundException("Supplier not found: " + id));
    }

    @Transactional
    public SupplierDTO create(SupplierRequest request) {
        String name = request.getName().trim();
        requireUniqueName(name);
        return toDTO(supplierRepository.save(Supplier.builder()
                .organisation(currentOrganisationService.current())
                .name(name)
                .build()));
    }

    @Transactional
    public SupplierDTO rename(Long id, SupplierRequest request) {
        Supplier supplier = require(id);
        String name = request.getName().trim();
        if (!name.equalsIgnoreCase(supplier.getName())) {
            requireUniqueName(name);
        }
        supplier.setName(name);
        return toDTO(supplierRepository.save(supplier));
    }

    /**
     * Only a supplier nothing was ever ordered from can be deleted — an order names its supplier
     * for good. The part numbers learned for it go with it.
     */
    @Transactional
    public void delete(Long id) {
        Supplier supplier = require(id);
        if (purchaseOrderRepository.existsBySupplierId(id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "There are orders at " + supplier.getName() + "; it cannot be deleted");
        }
        supplierRepository.delete(supplier);
    }

    private void requireUniqueName(String name) {
        if (supplierRepository.existsByOrganisationIdAndNameIgnoreCase(
                currentOrganisationService.currentId(), name)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A supplier named '" + name + "' already exists");
        }
    }

    static SupplierDTO toDTO(Supplier supplier) {
        return SupplierDTO.builder().id(supplier.getId()).name(supplier.getName()).build();
    }
}
