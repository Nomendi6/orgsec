package com.nomendi6.orgsec.storage.redis.protocol;

/** Raised after a staging write failed and its batch-local compensation completed. */
final class RedisStagingWriteException extends RedisProtocolException {

    static final String DIAGNOSTIC_CODE = "ORGSEC_REDIS_STAGING_WRITE_FAILED";

    RedisStagingWriteException() {
        super(DIAGNOSTIC_CODE + ": WRITE_FAILED_ROLLED_BACK");
    }
}
