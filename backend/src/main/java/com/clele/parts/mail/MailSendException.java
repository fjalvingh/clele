package com.clele.parts.mail;

/** A provider could not deliver a message. Always caught by the caller — mail is never fatal. */
public class MailSendException extends RuntimeException {

    public MailSendException(String message) {
        super(message);
    }

    public MailSendException(String message, Throwable cause) {
        super(message, cause);
    }
}
