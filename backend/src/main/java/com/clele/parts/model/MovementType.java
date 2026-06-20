package com.clele.parts.model;

/**
 * Why a stock movement happened. The ledger ({@link StockMovement}) is the source of truth for
 * on-hand quantity; this label makes each delta legible.
 */
public enum MovementType {
    /** Stock received / bought in. */
    PURCHASE,
    /** Stock used up. */
    CONSUME,
    /** Manual correction to match reality. */
    ADJUST,
    /** First movement that establishes an entry's starting quantity. */
    INITIAL,
    /** Written by the Partsbox importer replaying historical transactions. */
    IMPORT
}
