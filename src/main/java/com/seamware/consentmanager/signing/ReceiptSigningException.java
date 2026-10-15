package com.seamware.consentmanager.signing;

/**
 * A receipt could not be signed. Unchecked and no data-access error, so a write that must sign
 * rolls back rather than being taken for a lost race.
 */
public class ReceiptSigningException extends RuntimeException {

    public ReceiptSigningException(String message, Throwable cause) {
        super(message, cause);
    }
}
