package com.nomendi6.orgsec.exceptions;

/**
 * Security exception for the orgsec module.
 * This exception is thrown when security violations occur, such as insufficient privileges.
 * It replaces the dependency on external exception classes to make orgsec self-contained.
 */
public class OrgsecSecurityException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private String code;
    private String resource;
    private String operation;

    /**
     * Constructs a new security exception with the specified detail message.
     *
     * @param message the detail message
     */
    public OrgsecSecurityException(String message) {
        super(message);
    }

    /**
     * Constructs a new security exception with the specified detail message and cause.
     *
     * @param message the detail message
     * @param cause the cause of the exception
     */
    public OrgsecSecurityException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Constructs a new security exception with detailed information about the violation.
     *
     * @param message the detail message
     * @param code the error code
     * @param resource the resource that was accessed
     * @param operation the operation that was attempted
     */
    public OrgsecSecurityException(String message, String code, String resource, String operation) {
        super(message);
        this.code = code;
        this.resource = resource;
        this.operation = operation;
    }

    /**
     * Static factory method for creating an insufficient privileges exception.
     *
     * @param resource the resource that requires privileges
     * @param operation the operation that was attempted
     * @return a new OrgsecSecurityException
     */
    public static OrgsecSecurityException insufficientPrivileges(String resource, String operation) {
        String message = String.format("Insufficient privileges for operation '%s' on resource '%s'", operation, resource);
        return new OrgsecSecurityException(message, "INSUFFICIENT_PRIVILEGES", resource, operation);
    }

    /**
     * Static factory method for creating an access denied exception.
     *
     * @param message the detail message
     * @return a new OrgsecSecurityException
     */
    public static OrgsecSecurityException accessDenied(String message) {
        return new OrgsecSecurityException(message, "ACCESS_DENIED", null, null);
    }

    // Getters

    public String getCode() {
        return code;
    }

    public String getResource() {
        return resource;
    }

    public String getOperation() {
        return operation;
    }
}
