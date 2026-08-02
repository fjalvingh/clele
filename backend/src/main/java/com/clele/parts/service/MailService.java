package com.clele.parts.service;

import com.clele.parts.config.AppProperties;
import com.clele.parts.mail.EmailMessage;
import com.clele.parts.mail.MailProvider;
import com.clele.parts.mail.MailProviderRegistry;
import com.clele.parts.model.OrganisationInvitation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Composes the app's outgoing mails and hands them to whichever {@link MailProvider} is configured
 * ({@code app.mail.provider}) — this class knows what a mail says, never how it travels.
 *
 * <p>Sending is <em>optional</em>: with no provider selected, or one whose credentials are missing,
 * the message is logged instead of sent, so local development and a fresh install work without a
 * mail account. Every send reports whether it actually went out, and the caller surfaces that — an
 * invitation that silently vanished would leave the admin waiting for a reply that can never come.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MailService {

    private final MailProviderRegistry providers;
    private final AppProperties appProperties;

    /** Whether a configured provider is ready to send. */
    public boolean isEnabled() {
        return providers.active().isPresent();
    }

    /**
     * Send the invitation mail.
     *
     * @param link the accept/decline URL the invitee follows
     * @return whether the mail was actually sent
     */
    public boolean sendInvitation(OrganisationInvitation invitation, String link) {
        String organisation = invitation.getOrganisation().getName();
        String inviter = displayName(invitation);
        // Always the public product name here — never the "Clele" code name (see AppProperties).
        String app = appProperties.getPublicName();
        String subject = inviter + " invited you to " + organisation + " on " + app;
        String body = """
                Hello,

                %s has invited you to join the organisation "%s" in %s, the electronic parts
                stock management application.

                To accept or refuse this invitation, open:

                %s

                If you do not have an account yet, one will be created for you when you accept —
                you will be asked for your name, phone number and a password.

                If you were not expecting this invitation you can ignore this mail, or follow the
                link and refuse it. The invitation expires on %s.
                """.formatted(inviter, organisation, app, link,
                        invitation.getExpiresAt().toLocalDate());

        AppProperties.Mail config = appProperties.getMail();
        return send(EmailMessage.plain(config.getFrom(), config.getFromName(),
                invitation.getEmail(), subject, body), "invitation mail for "
                + invitation.getEmail() + ". Link: " + link);
    }

    /**
     * Deliver through the active provider, or log the message when there is none.
     *
     * @param logContext what to say when the mail could not go out — it must contain everything an
     *                   admin needs to act by hand (that is the whole point of logging it)
     * @return whether the mail was actually sent
     */
    private boolean send(EmailMessage message, String logContext) {
        Optional<MailProvider> provider = providers.active();
        if (provider.isEmpty()) {
            log.warn("No mail provider configured — {} not sent. {}", message.subject(),
                    logContext);
            return false;
        }
        try {
            provider.get().send(message);
            return true;
        } catch (Exception e) {
            // Never fail the caller over this: an invitation row is valid and its link works, so
            // the admin can still pass it on by hand. They are told it did not send.
            log.error("Provider '{}' failed to send to {}: {} — {}", provider.get().name(),
                    message.to(), e.getMessage(), logContext, e);
            return false;
        }
    }

    /** The inviting admin as the mail should name them; their email is the honest fallback. */
    private String displayName(OrganisationInvitation invitation) {
        if (invitation.getInvitedBy() == null) {
            return "An administrator";
        }
        String fullName = invitation.getInvitedBy().getFullName();
        return fullName != null && !fullName.isBlank()
                ? fullName
                : invitation.getInvitedBy().getEmail();
    }
}
