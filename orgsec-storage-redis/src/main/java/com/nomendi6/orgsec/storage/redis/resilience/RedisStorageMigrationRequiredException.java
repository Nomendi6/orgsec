package com.nomendi6.orgsec.storage.redis.resilience;

import com.nomendi6.orgsec.exceptions.OrgsecConfigurationException;

/**
 * Raised when Redis is enabled without the mandatory 1.1 snapshot/fence migration.
 */
public class RedisStorageMigrationRequiredException extends OrgsecConfigurationException {

    /** Stable diagnostic code for startup checks and generated applications. */
    public static final String DIAGNOSTIC_CODE =
        "ORGSEC_STORAGE_REDIS_R110_MIGRATION_REQUIRED";

    /**
     * Creates an actionable migration error.
     *
     * @param detail concrete missing or unsupported part of the Redis integration
     */
    public RedisStorageMigrationRequiredException(String detail) {
        super(DIAGNOSTIC_CODE + ": " + detail);
    }
}
