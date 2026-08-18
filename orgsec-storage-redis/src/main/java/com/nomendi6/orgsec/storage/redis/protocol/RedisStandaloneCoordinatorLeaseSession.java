package com.nomendi6.orgsec.storage.redis.protocol;

import java.util.Objects;

/** One owner-thread lease grant and its permanently dedicated Redis command stream. */
final class RedisStandaloneCoordinatorLeaseSession implements AutoCloseable {

    private final RedisPrimaryCommandStream stream;
    private final RedisStandaloneCoordinatorLeaseProtocol protocol;
    private final RedisStandaloneCoordinatorLeaseProtocol.Context context;
    private final long durationMillis;
    private final Thread ownerThread;

    private RedisCoordinatorLease.Verified lease;
    private volatile State state = State.ACTIVE;

    RedisStandaloneCoordinatorLeaseSession(
        RedisPrimaryCommandStream stream,
        RedisStandaloneCoordinatorLeaseProtocol protocol,
        RedisStandaloneCoordinatorLeaseProtocol.Context context,
        long durationMillis,
        RedisCoordinatorLease.Verified lease
    ) {
        this.stream = Objects.requireNonNull(stream, "stream must not be null");
        this.protocol = Objects.requireNonNull(protocol, "protocol must not be null");
        this.context = Objects.requireNonNull(context, "context must not be null");
        if (durationMillis <= 0
            || durationMillis >
                RedisStandaloneCoordinatorLeaseScripts.MAX_LEASE_DURATION_MILLIS) {
            throw new IllegalArgumentException("durationMillis is outside the supported range");
        }
        this.durationMillis = durationMillis;
        this.lease = requireActive(lease);
        this.ownerThread = Thread.currentThread();
    }

    /**
     * Returns the last exact transition post-image, not a live proof that the grant is still held.
     *
     * <p>Expiry, takeover or connection loss can make this historical handle stale immediately.
     * Every future protected Redis mutation must exact-check the whole handle, control context and
     * Redis time in the same atomic script.</p>
     */
    RedisCoordinatorLease.Verified lease() {
        requireOwnerAndActive("read the lease");
        return lease;
    }

    RedisCoordinatorLease.Verified renew() {
        requireOwnerAndActive("renew the lease");
        try {
            lease = protocol.renew(stream, context, lease, durationMillis);
            return lease;
        } catch (RuntimeException | Error failure) {
            poisonAndClose(failure);
            throw failure;
        }
    }

    RedisCoordinatorLease.Verified release() {
        requireOwnerAndActive("release the lease");
        final RedisCoordinatorLease.Verified released;
        try {
            released = protocol.release(stream, context, lease);
        } catch (RuntimeException | Error failure) {
            poisonAndClose(failure);
            throw failure;
        }
        lease = released;
        state = State.RELEASED;
        closeAfterTerminalTransition();
        return released;
    }

    long clientId() {
        requireOwnerAndActive("read the Redis client ID");
        try {
            return stream.clientId();
        } catch (RuntimeException | Error failure) {
            poisonAndClose(failure);
            throw failure;
        }
    }

    boolean isPoisoned() {
        return state == State.POISONED || stream.isPoisoned();
    }

    /**
     * Abandons an active grant without changing Redis state; expiry remains the only takeover path.
     */
    @Override
    public void close() {
        requireOwnerThread();
        if (state == State.RELEASED || state == State.ABANDONED) {
            return;
        }
        if (state == State.POISONED) {
            closeAlreadyPoisoned();
            return;
        }
        state = State.ABANDONED;
        try {
            stream.close();
        } catch (RuntimeException | Error failure) {
            state = State.POISONED;
            throw failure;
        }
    }

    private void closeAfterTerminalTransition() {
        try {
            stream.close();
        } catch (RuntimeException | Error failure) {
            state = State.POISONED;
            throw failure;
        }
    }

    private void poisonAndClose(Throwable pendingFailure) {
        state = State.POISONED;
        try {
            stream.close();
        } catch (RuntimeException | Error closeFailure) {
            pendingFailure.addSuppressed(closeFailure);
        }
    }

    private void closeAlreadyPoisoned() {
        try {
            stream.close();
        } catch (RuntimeException | Error failure) {
            state = State.POISONED;
            throw failure;
        }
    }

    private void requireOwnerAndActive(String operation) {
        requireOwnerThread();
        if (state != State.ACTIVE) {
            throw new IllegalStateException(
                "cannot " + operation + " when Redis standalone lease session is " + state
            );
        }
    }

    private void requireOwnerThread() {
        if (Thread.currentThread() != ownerThread) {
            throw new IllegalStateException(
                "Redis standalone lease session may only be used by its owner thread"
            );
        }
    }

    private static RedisCoordinatorLease.Verified requireActive(
        RedisCoordinatorLease.Verified value
    ) {
        Objects.requireNonNull(value, "lease must not be null");
        if (value.state() != RedisCoordinatorLease.State.ACTIVE) {
            throw new IllegalArgumentException("lease must be ACTIVE");
        }
        return value;
    }

    private enum State {
        ACTIVE,
        RELEASED,
        ABANDONED,
        POISONED
    }
}
