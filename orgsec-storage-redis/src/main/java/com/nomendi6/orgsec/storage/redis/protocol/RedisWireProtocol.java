package com.nomendi6.orgsec.storage.redis.protocol;

import com.nomendi6.orgsec.fence.SecurityDatasetFence;
import com.nomendi6.orgsec.fence.SecurityDatasetIdentity;

import java.util.Objects;

/** Shared protocol-version invariant for every Redis protocol-v1 wire boundary. */
final class RedisWireProtocol {

    static final int VERSION_ONE = 1;
    static final String UNSUPPORTED_VERSION_MESSAGE =
        "Redis wire protocol requires protocol version 1";

    private RedisWireProtocol() {
    }

    static SecurityDatasetIdentity requireVersionOne(SecurityDatasetIdentity identity) {
        Objects.requireNonNull(identity, "identity must not be null");
        requireVersionOne(identity.getProtocolVersion());
        return identity;
    }

    static SecurityDatasetFence requireVersionOne(SecurityDatasetFence sourceFence) {
        Objects.requireNonNull(sourceFence, "sourceFence must not be null");
        requireVersionOne(sourceFence.getIdentity());
        return sourceFence;
    }

    static void requireVersionOne(int protocolVersion) {
        if (!isVersionOne(protocolVersion)) {
            throw new IllegalArgumentException(UNSUPPORTED_VERSION_MESSAGE);
        }
    }

    static boolean isVersionOne(int protocolVersion) {
        return protocolVersion == VERSION_ONE;
    }
}
