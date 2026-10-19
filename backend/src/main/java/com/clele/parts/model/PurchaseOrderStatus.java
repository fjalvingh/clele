package com.clele.parts.model;

/** Where a {@link PurchaseOrder} is in its life. */
public enum PurchaseOrderStatus {
    /** Being put together and matched with the supplier: lines can be added, changed and replaced. */
    DRAFT,
    /** Placed with the supplier. The lines are fixed; deliveries are received against them. */
    ORDERED,
    /** Everything came in, or the order was closed with the rest written off. */
    RECEIVED,
    /** Abandoned. Its order list items went back to the list. */
    CANCELLED
}
