package com.nomendi6.orgsec.storage.redis.protocol;

import org.springframework.data.redis.connection.RedisConnectionFactory;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Internal entry point for the explicitly qualified {@code STANDALONE_NO_FAILOVER} lease profile.
 *
 * <p>The manager is deliberately not wired into publication. Every acquisition owns one direct,
 * non-reconnecting primary stream until release, abandonment or failure. Bootstrap or a future
 * control-generation transition must first install/rebind the exact persistent FREE lease and
 * counter records; creating those records is intentionally outside this transition-only slice.</p>
 */
final class RedisStandaloneCoordinatorLeaseManager implements AutoCloseable {

    private final StreamProvider streamProvider;
    private final RedisStandaloneCoordinatorLeaseProtocol protocol;
    private final UUID ownerSessionId;
    private final Supplier<UUID> acquisitionIds;
    private boolean closeStarted;
    private boolean closed;

    RedisStandaloneCoordinatorLeaseManager(RedisConnectionFactory sourceFactory) {
        this(
            new LettuceStreamProvider(sourceFactory),
            new RedisStandaloneCoordinatorLeaseProtocol(),
            UUID.randomUUID(),
            UUID::randomUUID
        );
    }

    RedisStandaloneCoordinatorLeaseManager(
        StreamProvider streamProvider,
        RedisStandaloneCoordinatorLeaseProtocol protocol,
        UUID ownerSessionId,
        Supplier<UUID> acquisitionIds
    ) {
        this.streamProvider = Objects.requireNonNull(
            streamProvider,
            "streamProvider must not be null"
        );
        this.protocol = Objects.requireNonNull(protocol, "protocol must not be null");
        this.ownerSessionId = requireUuidV4(ownerSessionId, "ownerSessionId");
        this.acquisitionIds = Objects.requireNonNull(
            acquisitionIds,
            "acquisitionIds must not be null"
        );
    }

    /**
     * Attempts one atomic acquisition against the caller's exact primary/control snapshot.
     *
     * <p>A busy result is authoritative only for this instant and returns an empty value. Any
     * exception after the stream opens closes that stream because a transition result may be
     * ambiguous.</p>
     */
    synchronized Optional<RedisStandaloneCoordinatorLeaseSession> tryAcquire(
        RedisPrimarySnapshot expectedPrimary,
        Duration duration
    ) {
        requireOpen();
        RedisStandaloneCoordinatorLeaseProtocol.Context context = protocol.context(
            expectedPrimary
        );
        long durationMillis = protocol.durationMillis(duration);
        UUID acquisitionId = requireUuidV4(
            acquisitionIds.get(),
            "generated acquisitionId"
        );

        RedisPrimaryCommandStream stream = streamProvider.open();
        try {
            RedisStandaloneCoordinatorLeaseProtocol.AcquireOutcome outcome =
                protocol.acquire(
                    stream,
                    context,
                    ownerSessionId,
                    acquisitionId,
                    durationMillis
                );
            if (!outcome.isAcquired()) {
                closeStream(stream, null);
                return Optional.empty();
            }
            return Optional.of(new RedisStandaloneCoordinatorLeaseSession(
                stream,
                protocol,
                context,
                durationMillis,
                outcome.lease()
            ));
        } catch (RuntimeException | Error failure) {
            closeStream(stream, failure);
            throw failure;
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closeStarted = true;
        streamProvider.close();
        closed = true;
    }

    private void requireOpen() {
        if (closeStarted) {
            throw new IllegalStateException("Redis standalone lease manager is closed");
        }
    }

    private static UUID requireUuidV4(UUID value, String name) {
        if (value == null || value.version() != 4 || value.variant() != 2) {
            throw new IllegalArgumentException(name + " must be an RFC 4122 UUIDv4");
        }
        return value;
    }

    private static void closeStream(
        RedisPrimaryCommandStream stream,
        Throwable pendingFailure
    ) {
        try {
            stream.close();
        } catch (RuntimeException | Error closeFailure) {
            if (pendingFailure != null) {
                pendingFailure.addSuppressed(closeFailure);
                return;
            }
            throw closeFailure;
        }
    }

    interface StreamProvider extends AutoCloseable {

        RedisPrimaryCommandStream open();

        @Override
        void close();
    }

    private static final class LettuceStreamProvider implements StreamProvider {

        private final LettuceNonReconnectingPrimaryStreamFactory delegate;

        private LettuceStreamProvider(RedisConnectionFactory sourceFactory) {
            delegate = new LettuceNonReconnectingPrimaryStreamFactory(sourceFactory);
        }

        @Override
        public RedisPrimaryCommandStream open() {
            return delegate.open();
        }

        @Override
        public void close() {
            delegate.close();
        }
    }
}
