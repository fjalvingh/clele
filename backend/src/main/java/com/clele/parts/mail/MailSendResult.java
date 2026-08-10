package com.clele.parts.mail;

/**
 * The outcome of one send attempt: whether it went out, and — when it did not — why, phrased for
 * the admin who triggered it.
 *
 * <p>The reason matters. "No mail could be sent" alone sends an admin off configuring a mail server
 * that is already configured and merely refusing the message (a wrong password, an unauthorised
 * sending IP, a rejected from-address). Saying which of the two it is turns a dead end into a fix.
 */
public record MailSendResult(boolean sent, String failureReason) {

    private static final MailSendResult SUCCEEDED = new MailSendResult(true, null);

    public static MailSendResult succeeded() {
        return SUCCEEDED;
    }

    /** @param reason a complete sentence — it is shown verbatim in the UI */
    public static MailSendResult failed(String reason) {
        return new MailSendResult(false, reason);
    }
}
