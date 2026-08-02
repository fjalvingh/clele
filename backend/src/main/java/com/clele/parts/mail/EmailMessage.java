package com.clele.parts.mail;

/**
 * One outgoing mail, in provider-neutral form. Everything a {@link MailProvider} needs and nothing
 * that ties the message to how it is delivered.
 *
 * @param from      sender address
 * @param fromName  display name for the sender, may be {@code null}
 * @param to        recipient address
 * @param subject   subject line
 * @param text      plain-text body (always present — it is the fallback every provider accepts)
 * @param html      HTML body, may be {@code null}
 */
public record EmailMessage(
        String from,
        String fromName,
        String to,
        String subject,
        String text,
        String html
) {
    public static EmailMessage plain(String from, String fromName, String to, String subject,
                                     String text) {
        return new EmailMessage(from, fromName, to, subject, text, null);
    }
}
