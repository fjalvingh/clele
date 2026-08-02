package com.clele.parts.model;

/** Lifecycle of an {@link OrganisationInvitation}. An invitation is answered exactly once. */
public enum InvitationStatus {
    /** Sent, not yet answered. May still expire. */
    PENDING,
    /** The invitee joined the organisation. */
    ACCEPTED,
    /** The invitee refused. */
    DECLINED,
    /** Withdrawn by an Organisation Admin before it was answered. */
    REVOKED
}
