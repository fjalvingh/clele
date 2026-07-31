package com.clele.parts.model;

/** Lifecycle of a self-registered print daemon: unclaimed, or claimed and usable by its owner. */
public enum DaemonStatus {
    PENDING,
    ACTIVE
}
