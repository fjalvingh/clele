package com.clele.parts.model;

/** State of a queued print job as it moves from the web app to the daemon and the printer. */
public enum JobStatus {
    QUEUED,
    SENT,
    DONE,
    FAILED
}
