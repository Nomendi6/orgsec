package com.nomendi6.orgsec.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "orgsec")
public class OrgsecProperties {

    /**
     * Whether OrgSec security is enabled
     */
    private boolean enabled = true;

    /**
     * Storage configuration
     */
    private Storage storage = new Storage();

    /**
     * Security configuration
     */
    private Security security = new Security();

    /**
     * Feature flags
     */
    private Features features = new Features();

    // Getters and Setters
    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Storage getStorage() {
        return storage;
    }

    public void setStorage(Storage storage) {
        this.storage = storage;
    }

    public Security getSecurity() {
        return security;
    }

    public void setSecurity(Security security) {
        this.security = security;
    }

    public Features getFeatures() {
        return features;
    }

    public void setFeatures(Features features) {
        this.features = features;
    }

    /**
     * Storage configuration
     */
    public static class Storage {
        /**
         * Storage type: inmemory, jwt, redis
         */
        private String type = "inmemory";

        /**
         * InMemory storage configuration
         */
        private InMemory inmemory = new InMemory();

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public InMemory getInmemory() {
            return inmemory;
        }

        public void setInmemory(InMemory inmemory) {
            this.inmemory = inmemory;
        }

        public static class InMemory {
            private int cacheTtl = 3600;
            private int maxEntries = 10000;

            public int getCacheTtl() {
                return cacheTtl;
            }

            public void setCacheTtl(int cacheTtl) {
                this.cacheTtl = cacheTtl;
            }

            public int getMaxEntries() {
                return maxEntries;
            }

            public void setMaxEntries(int maxEntries) {
                this.maxEntries = maxEntries;
            }
        }
    }

    /**
     * Security configuration
     */
    public static class Security {
        private boolean privilegeChecking = true;
        private boolean roleHierarchy = true;
        private boolean auditLogging = false;

        public boolean isPrivilegeChecking() {
            return privilegeChecking;
        }

        public void setPrivilegeChecking(boolean privilegeChecking) {
            this.privilegeChecking = privilegeChecking;
        }

        public boolean isRoleHierarchy() {
            return roleHierarchy;
        }

        public void setRoleHierarchy(boolean roleHierarchy) {
            this.roleHierarchy = roleHierarchy;
        }

        public boolean isAuditLogging() {
            return auditLogging;
        }

        public void setAuditLogging(boolean auditLogging) {
            this.auditLogging = auditLogging;
        }
    }

    /**
     * Feature configuration
     */
    public static class Features {
        private boolean businessRoles = true;
        private boolean positionRoles = true;
        private boolean delegations = false;

        public boolean isBusinessRoles() {
            return businessRoles;
        }

        public void setBusinessRoles(boolean businessRoles) {
            this.businessRoles = businessRoles;
        }

        public boolean isPositionRoles() {
            return positionRoles;
        }

        public void setPositionRoles(boolean positionRoles) {
            this.positionRoles = positionRoles;
        }

        public boolean isDelegations() {
            return delegations;
        }

        public void setDelegations(boolean delegations) {
            this.delegations = delegations;
        }
    }
}