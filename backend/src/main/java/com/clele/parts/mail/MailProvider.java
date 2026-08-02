package com.clele.parts.mail;

/**
 * A way of getting mail out of the app. One implementation per delivery channel (SMTP, MailerSend,
 * …); which one is used is a matter of configuration ({@code app.mail.provider}), so switching
 * providers is a config change and never a code change.
 *
 * <p>Implementations are Spring beans and are discovered by {@link MailProviderRegistry} — adding a
 * provider means adding a class, nothing else.
 */
public interface MailProvider {

    /**
     * The configuration name selecting this provider ({@code app.mail.provider}), lower-case and
     * stable — it appears in config files.
     */
    String name();

    /**
     * Whether this provider has everything it needs to actually send (host, API key, …). A selected
     * but unconfigured provider is not an error: the app then logs mails instead of sending them,
     * which is what a fresh install and local development want.
     */
    boolean isConfigured();

    /**
     * Send the message.
     *
     * @throws MailSendException when the provider rejected or could not deliver the message
     */
    void send(EmailMessage message) throws MailSendException;
}
