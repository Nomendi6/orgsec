package com.nomendi6.orgsec.storage.redis.protocol;

import com.nomendi6.orgsec.fence.SecurityDatasetFence;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import java.util.Objects;

/**
 * Opens owner-thread, append-only staging sessions guarded by an exact primary/control snapshot.
 *
 * <p>One {@link RedisConnection} object is acquired once and owned exclusively by the returned
 * session until it closes. Spring's generic factory contract does not prove that a Lettuce wrapper
 * owns a physically non-shared native command stream. That distinction is harmless for this
 * class's atomic EVAL-only staging writes; lease and publication transitions use the dedicated
 * direct-primary stream instead.</p>
 */
final class StrictRedisSnapshotWriter {

    private final RedisConnectionFactory connectionFactory;
    private final RedisSnapshotWriteLimits limits;
    private final RedisStagingSession.ScriptEvaluator evaluator;
    private final RedisStagingSession.EntryVerifier entryVerifier;
    private final RedisControlEnvelopeCodec controlCodec;
    private final RedisCoordinatorLeaseCodec leaseCodec;
    private final RedisSnapshotManifestCodec manifestCodec;

    StrictRedisSnapshotWriter(
        RedisConnectionFactory connectionFactory,
        RedisSnapshotWriteLimits limits
    ) {
        this(
            connectionFactory,
            limits,
            RedisStagingSession::evaluateOnConnection,
            new RedisControlEnvelopeCodec(),
            new RedisCoordinatorLeaseCodec(),
            new RedisSnapshotManifestCodec(),
            new RedisCanonicalSnapshotEntryVerifier()::verify
        );
    }

    StrictRedisSnapshotWriter(
        RedisConnectionFactory connectionFactory,
        RedisSnapshotWriteLimits limits,
        RedisStagingSession.ScriptEvaluator evaluator,
        RedisControlEnvelopeCodec controlCodec,
        RedisSnapshotManifestCodec manifestCodec,
        RedisStagingSession.EntryVerifier entryVerifier
    ) {
        this(
            connectionFactory,
            limits,
            evaluator,
            controlCodec,
            new RedisCoordinatorLeaseCodec(),
            manifestCodec,
            entryVerifier
        );
    }

    StrictRedisSnapshotWriter(
        RedisConnectionFactory connectionFactory,
        RedisSnapshotWriteLimits limits,
        RedisStagingSession.ScriptEvaluator evaluator,
        RedisControlEnvelopeCodec controlCodec,
        RedisCoordinatorLeaseCodec leaseCodec,
        RedisSnapshotManifestCodec manifestCodec,
        RedisStagingSession.EntryVerifier entryVerifier
    ) {
        this.connectionFactory = Objects.requireNonNull(
            connectionFactory,
            "connectionFactory must not be null"
        );
        this.limits = Objects.requireNonNull(limits, "limits must not be null");
        this.evaluator = Objects.requireNonNull(evaluator, "evaluator must not be null");
        this.controlCodec = Objects.requireNonNull(
            controlCodec,
            "controlCodec must not be null"
        );
        this.leaseCodec = Objects.requireNonNull(leaseCodec, "leaseCodec must not be null");
        this.manifestCodec = Objects.requireNonNull(
            manifestCodec,
            "manifestCodec must not be null"
        );
        this.entryVerifier = Objects.requireNonNull(
            entryVerifier,
            "entryVerifier must not be null"
        );
    }

    /**
     * Opens a fresh staging snapshot under the exact already-observed generation.
     *
     * @param expectedPrimary primary and control record observed atomically before staging
     * @param sourceFence exact source-database fence held by the future coordinator
     * @return session owning a library-generated UUIDv4 and one acquired connection
     */
    RedisStagingSession begin(
        RedisPrimarySnapshot expectedPrimary,
        SecurityDatasetFence sourceFence,
        RedisCoordinatorLease.Verified activeLease
    ) {
        Objects.requireNonNull(expectedPrimary, "expectedPrimary must not be null");
        RedisWireProtocol.requireVersionOne(sourceFence);

        RedisStagingSession.GenerationGuard guard = RedisStagingSession.GenerationGuard.create(
            expectedPrimary,
            sourceFence,
            activeLease,
            controlCodec,
            leaseCodec
        );
        RedisSnapshotManifest.PendingPublication publication =
            RedisSnapshotManifest.beginPublication(sourceFence);
        RedisDatasetKeyspace keyspace = new RedisDatasetKeyspace(
            sourceFence.getIdentity().getSecurityDatasetId()
        );

        RedisConnection connection = acquireConnection();
        try {
            return new RedisStagingSession(
                connection,
                evaluator,
                keyspace,
                limits,
                guard,
                publication,
                manifestCodec,
                entryVerifier
            );
        } catch (RuntimeException | Error failure) {
            closeAfterConstructionFailure(connection, failure);
            throw failure;
        }
    }

    private RedisConnection acquireConnection() {
        final RedisConnection connection;
        try {
            connection = connectionFactory.getConnection();
        } catch (RuntimeException failure) {
            throw new RedisProtocolUnavailableException(
                "Failed to acquire a Redis staging-session connection.",
                failure
            );
        }
        if (connection == null) {
            throw new RedisProtocolUnavailableException(
                "RedisConnectionFactory returned no staging-session connection."
            );
        }
        return connection;
    }

    private static void closeAfterConstructionFailure(
        RedisConnection connection,
        Throwable pendingFailure
    ) {
        try {
            connection.close();
        } catch (RuntimeException closeFailure) {
            pendingFailure.addSuppressed(new RedisProtocolUnavailableException(
                "Failed to close an unused Redis staging-session connection.",
                closeFailure
            ));
        }
    }
}
