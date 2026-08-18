package com.nomendi6.orgsec.storage.redis.protocol;

/**
 * Raised when the connected Redis node is not a supported physical primary.
 */
public final class RedisProtocolTopologyException extends RedisProtocolException {

    /** Stable diagnostic code for unsupported Redis topology. */
    public static final String DIAGNOSTIC_CODE = "ORGSEC_REDIS_PROTOCOL_TOPOLOGY";

    /**
     * Creates a topology error.
     *
     * @param detail unsupported or indeterminate topology detail
     */
    public RedisProtocolTopologyException(String detail) {
        super(DIAGNOSTIC_CODE + ": " + detail);
    }
}
