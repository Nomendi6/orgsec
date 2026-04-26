package com.nomendi6.orgsec.exceptions;

/**
 * Exception thrown when there is a data access error in the orgsec module.
 * This exception provides better context than generic RuntimeException for
 * data access related problems.
 */
public class OrgsecDataAccessException extends RuntimeException {

    public OrgsecDataAccessException(String message) {
        super(message);
    }

    public OrgsecDataAccessException(String message, Throwable cause) {
        super(message, cause);
    }

    public OrgsecDataAccessException(Throwable cause) {
        super(cause);
    }
}
