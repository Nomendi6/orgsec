package com.nomendi6.orgsec.exceptions;

/**
 * Exception thrown when there is a configuration error in the orgsec module.
 * This exception provides better context than generic RuntimeException for
 * configuration-related problems.
 */
public class OrgsecConfigurationException extends RuntimeException {

    public OrgsecConfigurationException(String message) {
        super(message);
    }

    public OrgsecConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }

    public OrgsecConfigurationException(Throwable cause) {
        super(cause);
    }
}
