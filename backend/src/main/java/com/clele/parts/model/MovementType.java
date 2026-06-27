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
    /** Transfer of stock between two locations (one negative leg, one positive leg). */
    MOVE,
    /** Written by the Partsbox importer replaying historical transactions. */
    IMPORT,
    /** Stock pulled from a location into a project during the BUILDING phase. */
    PROJECT_OUT,
    /** Stock returned from a cancelled project back to its source location. */
    PROJECT_RETURN
}
