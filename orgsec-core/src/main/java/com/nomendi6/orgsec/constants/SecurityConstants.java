package com.nomendi6.orgsec.constants;

/**
 * Central location for all security-related constants.
 * Reduces magic strings throughout the codebase and provides type safety.
 */
public final class SecurityConstants {

    private SecurityConstants() {
        // Utility class
    }

    /**
     * Business role names used throughout the security system
     */
    public static final class BusinessRoles {

        public static final String OWNER = "owner";
        public static final String CUSTOMER = "customer";
        public static final String CONTRACTOR = "contractor";

        private BusinessRoles() {}
    }

    /**
     * Error messages used in security exceptions
     */
    public static final class ErrorMessages {

        public static final String INSUFFICIENT_PRIVILEGES = "Insufficient privileges";
        public static final String NO_PRIVILEGE_CODE = "nopriv";
        public static final String UNKNOWN_OPERATION = "Unknown operation";
        public static final String NO_USER_LOGGED_IN = "No user is logged in";

        private ErrorMessages() {}
    }

    /**
     * Security field types for different contexts
     */
    public static final class SecurityFields {

        public static final String COMPANY = "COMPANY";
        public static final String COMPANY_PATH = "COMPANY_PATH";
        public static final String ORG = "ORG";
        public static final String ORG_PATH = "ORG_PATH";
        public static final String PERSON = "PERSON";

        private SecurityFields() {}
    }

    /**
     * Event type constants for security-related events
     */
    public static final class EventTypes {

        public static final String PARTY_ROLE_ADDED = "PARTY_ROLE_ADDED";
        public static final String PARTY_ROLE_CHANGED = "PARTY_ROLE_CHANGED";
        public static final String PARTY_ROLE_DELETED = "PARTY_ROLE_DELETED";
        public static final String POSITION_ROLE_ADDED = "POSITION_ROLE_ADDED";
        public static final String POSITION_ROLE_CHANGED = "POSITION_ROLE_CHANGED";
        public static final String POSITION_ROLE_DELETED = "POSITION_ROLE_DELETED";
        public static final String PARTY_ADDED = "PARTY_ADDED";
        public static final String PARTY_CHANGED = "PARTY_CHANGED";
        public static final String PARTY_DELETED = "PARTY_DELETED";
        public static final String PERSON_ADDED = "PERSON_ADDED";
        public static final String PERSON_CHANGED = "PERSON_CHANGED";
        public static final String PERSON_DELETED = "PERSON_DELETED";

        private EventTypes() {}
    }

    /**
     * Privilege operation validation messages
     */
    public static final class PrivilegeValidation {

        public static final String WRITE_INCLUDES_READ = "WRITE privilege includes READ privilege";
        public static final String INVALID_OPERATION = "Invalid privilege operation";
        public static final String INVALID_DIRECTION = "Invalid privilege direction";

        private PrivilegeValidation() {}
    }
}
