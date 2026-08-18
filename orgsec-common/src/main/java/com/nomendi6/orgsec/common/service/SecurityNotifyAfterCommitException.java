package com.nomendi6.orgsec.common.service;

/**
 * Raised when a producer notify/publish fails after the source transaction has already committed.
 * The database change is durable; the authorization view may still be stale.
 */
public class SecurityNotifyAfterCommitException extends RuntimeException {

    public SecurityNotifyAfterCommitException(Throwable cause) {
        super("Security notify failed after the source transaction committed", cause);
    }
}
