package com.nomendi6.orgsec.storage.redis.protocol;

import com.nomendi6.orgsec.fence.SecurityDatasetIdentity;
import io.lettuce.core.KillArgs;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.api.sync.RedisCommands;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.DataType;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.ReturnType;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class RedisStandaloneCoordinatorLeaseManagerIntegrationTest {

    private static final int REDIS_PORT = 6379;
    private static final UUID STORAGE_UUID =
        UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");

    private static final byte[] REDIS_TIME_MILLIS = ascii(
        "local t = redis.call('TIME') " +
            "return (tonumber(t[1]) * 1000) + math.floor(tonumber(t[2]) / 1000)"
    );

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(
        DockerImageName.parse("redis:6.0-alpine")
    ).withExposedPorts(REDIS_PORT)
        .withCommand("redis-server", "--save", "", "--appendonly", "no");

    private static LettuceConnectionFactory sourceFactory;
    private static StringRedisTemplate redis;

    private final RedisControlEnvelopeCodec controlCodec = new RedisControlEnvelopeCodec();
    private final RedisCoordinatorLeaseCodec leaseCodec =
        new RedisCoordinatorLeaseCodec();

    @BeforeAll
    static void createSourceFactory() {
        RedisStandaloneConfiguration server = new RedisStandaloneConfiguration(
            REDIS.getHost(),
            REDIS.getMappedPort(REDIS_PORT)
        );
        sourceFactory = new LettuceConnectionFactory(server);
        sourceFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(sourceFactory);
        redis.afterPropertiesSet();

        try (RedisConnection connection = sourceFactory.getConnection()) {
            assertThat(connection.serverCommands().info("server").getProperty("redis_version"))
                .startsWith("6.0.");
        }
    }

    @AfterAll
    static void destroySourceFactory() {
        if (sourceFactory != null) {
            sourceFactory.destroy();
        }
    }

    @BeforeEach
    void clearRedis() {
        try (RedisConnection connection = sourceFactory.getConnection()) {
            connection.serverCommands().flushAll();
        }
    }

    @Test
    void lifecycleUsesPersistentMonotonicFenceAcrossReleaseAndExpiry() {
        Fixture fixture = fixture(0, 0, 0);
        try (RedisStandaloneCoordinatorLeaseManager manager =
                 new RedisStandaloneCoordinatorLeaseManager(sourceFactory)) {
            RedisStandaloneCoordinatorLeaseSession first = manager.tryAcquire(
                fixture.primary(),
                Duration.ofSeconds(2)
            ).orElseThrow();
            RedisCoordinatorLease.Verified acquired = first.lease();
            assertThat(acquired.revision()).isEqualTo(1);
            assertThat(acquired.fencingSequence()).isEqualTo(1);
            assertThat(acquired.expiresAtRedisMillis() - acquired.issuedAtRedisMillis())
                .isEqualTo(2_000);

            RedisCoordinatorLease.Verified renewed = first.renew();
            assertThat(renewed.revision()).isEqualTo(2);
            assertThat(renewed.fencingSequence()).isEqualTo(1);
            assertThat(renewed.expiresAtRedisMillis())
                .isGreaterThanOrEqualTo(acquired.expiresAtRedisMillis());

            RedisCoordinatorLease.Verified released = first.release();
            assertThat(released.state()).isEqualTo(RedisCoordinatorLease.State.FREE);
            assertThat(released.revision()).isEqualTo(3);
            assertThat(released.fencingSequence()).isEqualTo(1);
            assertThat(released.ownerSessionId()).isNull();
            assertThat(released.acquisitionId()).isNull();
            assertThat(released.issuedAtRedisMillis()).isZero();
            assertThat(released.expiresAtRedisMillis()).isZero();
            assertPersistentMetadata(fixture, "1");

            RedisStandaloneCoordinatorLeaseSession second = manager.tryAcquire(
                fixture.primary(),
                Duration.ofMillis(500)
            ).orElseThrow();
            RedisCoordinatorLease.Verified secondLease = second.lease();
            assertThat(secondLease.revision()).isEqualTo(4);
            assertThat(secondLease.fencingSequence()).isEqualTo(2);

            Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(10))
                .until(() -> redisTimeMillis() >= secondLease.expiresAtRedisMillis());

            RedisStandaloneCoordinatorLeaseSession third = manager.tryAcquire(
                fixture.primary(),
                Duration.ofSeconds(2)
            ).orElseThrow();
            RedisCoordinatorLease.Verified thirdLease = third.lease();
            assertThat(thirdLease.revision()).isEqualTo(5);
            assertThat(thirdLease.fencingSequence()).isEqualTo(3);
            assertThat(thirdLease.issuedAtRedisMillis())
                .isGreaterThanOrEqualTo(secondLease.expiresAtRedisMillis());

            assertTransitionFailure(
                second::release,
                RedisCoordinatorLeaseTransitionException.Reason.LEASE_LOST
            );
            assertThat(second.isPoisoned()).isTrue();
            RedisCoordinatorLease.Unverified stillThird = readLease(fixture);
            assertThat(stillThird.fencingSequence()).isEqualTo(3);
            assertThat(stillThird.acquisitionId()).isEqualTo(thirdLease.acquisitionId());

            RedisCoordinatorLease.Verified finalFree = third.release();
            assertThat(finalFree.revision()).isEqualTo(6);
            assertThat(finalFree.fencingSequence()).isEqualTo(3);
            assertPersistentMetadata(fixture, "3");
        }
    }

    @Test
    void simultaneousContendersHaveExactlyOneWinner() throws Exception {
        Fixture fixture = fixture(0, 0, 0);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch attempted = new CountDownLatch(2);
        CountDownLatch finish = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<Attempt>> futures = new ArrayList<>();
            for (int index = 0; index < 2; index++) {
                futures.add(executor.submit(() -> contend(
                    fixture.primary(),
                    ready,
                    start,
                    attempted,
                    finish
                )));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(attempted.await(5, TimeUnit.SECONDS)).isTrue();
            finish.countDown();

            List<Attempt> results = new ArrayList<>();
            for (Future<Attempt> future : futures) {
                results.add(future.get(5, TimeUnit.SECONDS));
            }
            assertThat(results).filteredOn(Attempt::acquired).hasSize(1);
            assertThat(results).filteredOn(Attempt::acquired)
                .extracting(Attempt::fencingSequence)
                .containsExactly(1L);
            assertThat(results).filteredOn(result -> !result.acquired()).hasSize(1);
            assertThat(redis.opsForValue().get(fixture.keyspace().leaseCounterKey()))
                .isEqualTo("1");
        } finally {
            finish.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void counterGapsAreNeverReusedAndBothExhaustionBoundariesAreAtomic() {
        Fixture gap = fixture(4, 2, 5);
        try (RedisStandaloneCoordinatorLeaseManager manager =
                 new RedisStandaloneCoordinatorLeaseManager(sourceFactory)) {
            RedisStandaloneCoordinatorLeaseSession session = manager.tryAcquire(
                gap.primary(),
                Duration.ofSeconds(2)
            ).orElseThrow();
            assertThat(session.lease().fencingSequence()).isEqualTo(6);
            assertThat(session.release().fencingSequence()).isEqualTo(6);
            assertThat(redis.opsForValue().get(gap.keyspace().leaseCounterKey()))
                .isEqualTo("6");
        }

        clearRedis();
        long maximum = RedisCoordinatorLease.MAX_LUA_SAFE_INTEGER;
        Fixture counterMaximum = fixture(0, maximum - 1, maximum - 1);
        try (RedisStandaloneCoordinatorLeaseManager manager =
                 new RedisStandaloneCoordinatorLeaseManager(sourceFactory)) {
            RedisStandaloneCoordinatorLeaseSession session = manager.tryAcquire(
                counterMaximum.primary(),
                Duration.ofSeconds(2)
            ).orElseThrow();
            assertThat(session.lease().fencingSequence()).isEqualTo(maximum);
            session.release();
            DatasetState before = state(counterMaximum);

            assertTransitionFailure(
                () -> manager.tryAcquire(counterMaximum.primary(), Duration.ofSeconds(2)),
                RedisCoordinatorLeaseTransitionException.Reason.COUNTER_EXHAUSTED
            );
            assertThat(state(counterMaximum)).isEqualTo(before);
        }

        clearRedis();
        Fixture revisionMaximum = fixture(maximum, 0, 0);
        DatasetState before = state(revisionMaximum);
        try (RedisStandaloneCoordinatorLeaseManager manager =
                 new RedisStandaloneCoordinatorLeaseManager(sourceFactory)) {
            assertTransitionFailure(
                () -> manager.tryAcquire(revisionMaximum.primary(), Duration.ofSeconds(2)),
                RedisCoordinatorLeaseTransitionException.Reason.REVISION_EXHAUSTED
            );
        }
        assertThat(state(revisionMaximum)).isEqualTo(before);
    }

    @Test
    void renewAndReleaseAtMaximumRevisionFailWithoutMutation() {
        long maximum = RedisCoordinatorLease.MAX_LUA_SAFE_INTEGER;
        Fixture fixture = fixture(maximum - 1, 0, 0);
        try (RedisStandaloneCoordinatorLeaseManager manager =
                 new RedisStandaloneCoordinatorLeaseManager(sourceFactory)) {
            RedisStandaloneCoordinatorLeaseSession session = manager.tryAcquire(
                fixture.primary(),
                Duration.ofSeconds(2)
            ).orElseThrow();
            assertThat(session.lease().revision()).isEqualTo(maximum);
            DatasetState before = state(fixture);

            assertTransitionFailure(
                session::renew,
                RedisCoordinatorLeaseTransitionException.Reason.REVISION_EXHAUSTED
            );
            assertThat(session.isPoisoned()).isTrue();
            assertThat(state(fixture)).isEqualTo(before);
        }

        clearRedis();
        Fixture releaseFixture = fixture(maximum - 1, 0, 0);
        try (RedisStandaloneCoordinatorLeaseManager manager =
                 new RedisStandaloneCoordinatorLeaseManager(sourceFactory)) {
            RedisStandaloneCoordinatorLeaseSession session = manager.tryAcquire(
                releaseFixture.primary(),
                Duration.ofSeconds(2)
            ).orElseThrow();
            assertThat(session.lease().revision()).isEqualTo(maximum);
            DatasetState before = state(releaseFixture);

            assertTransitionFailure(
                session::release,
                RedisCoordinatorLeaseTransitionException.Reason.REVISION_EXHAUSTED
            );
            assertThat(session.isPoisoned()).isTrue();
            assertThat(state(releaseFixture)).isEqualTo(before);
        }
    }

    @Test
    void boundedPreflightRejectsMissingWrongTypeTtlAndOversizedStateWithoutMutation() {
        List<CorruptionCase> cases = List.of(
            new CorruptionCase(
                "missing control",
                value -> redis.delete(value.keyspace().controlKey()),
                RedisCoordinatorLeaseTransitionException.Reason.CONTROL_CHANGED
            ),
            new CorruptionCase(
                "wrong control type",
                value -> replaceWithString(value.keyspace().controlKey(), "wrong-type"),
                RedisCoordinatorLeaseTransitionException.Reason.CONTROL_CHANGED
            ),
            new CorruptionCase(
                "control ttl",
                value -> redis.expire(value.keyspace().controlKey(), Duration.ofMinutes(1)),
                RedisCoordinatorLeaseTransitionException.Reason.CONTROL_CHANGED
            ),
            new CorruptionCase(
                "oversized control field",
                value -> redis.opsForHash().put(
                    value.keyspace().controlKey(),
                    RedisControlEnvelopeCodec.FIELD_DATASET_ID,
                    "x".repeat(257)
                ),
                RedisCoordinatorLeaseTransitionException.Reason.CONTROL_CHANGED
            ),
            new CorruptionCase(
                "unknown control field",
                value -> redis.opsForHash().put(
                    value.keyspace().controlKey(),
                    "unknown",
                    "value"
                ),
                RedisCoordinatorLeaseTransitionException.Reason.CONTROL_CHANGED
            ),
            new CorruptionCase(
                "missing lease",
                value -> redis.delete(value.keyspace().leaseKey()),
                RedisCoordinatorLeaseTransitionException.Reason.LEASE_CORRUPT
            ),
            new CorruptionCase(
                "wrong lease type",
                value -> replaceWithString(value.keyspace().leaseKey(), "wrong-type"),
                RedisCoordinatorLeaseTransitionException.Reason.LEASE_CORRUPT
            ),
            new CorruptionCase(
                "lease ttl",
                value -> redis.expire(
                    value.keyspace().leaseKey(),
                    Duration.ofMinutes(1)
                ),
                RedisCoordinatorLeaseTransitionException.Reason.LEASE_CORRUPT
            ),
            new CorruptionCase(
                "oversized lease field",
                value -> redis.opsForHash().put(
                    value.keyspace().leaseKey(),
                    RedisCoordinatorLeaseCodec.FIELD_DATASET_ID,
                    "x".repeat(257)
                ),
                RedisCoordinatorLeaseTransitionException.Reason.LEASE_CORRUPT
            ),
            new CorruptionCase(
                "unknown lease field",
                value -> redis.opsForHash().put(
                    value.keyspace().leaseKey(),
                    "unknown",
                    "value"
                ),
                RedisCoordinatorLeaseTransitionException.Reason.LEASE_CORRUPT
            ),
            new CorruptionCase(
                "missing counter",
                value -> redis.delete(value.keyspace().leaseCounterKey()),
                RedisCoordinatorLeaseTransitionException.Reason.LEASE_CORRUPT
            ),
            new CorruptionCase(
                "wrong counter type",
                value -> {
                    redis.delete(value.keyspace().leaseCounterKey());
                    redis.opsForHash().put(
                        value.keyspace().leaseCounterKey(),
                        "field",
                        "value"
                    );
                },
                RedisCoordinatorLeaseTransitionException.Reason.LEASE_CORRUPT
            ),
            new CorruptionCase(
                "counter ttl",
                value -> redis.expire(
                    value.keyspace().leaseCounterKey(),
                    Duration.ofMinutes(1)
                ),
                RedisCoordinatorLeaseTransitionException.Reason.LEASE_CORRUPT
            ),
            new CorruptionCase(
                "oversized counter",
                value -> redis.opsForValue().set(
                    value.keyspace().leaseCounterKey(),
                    "9".repeat(17)
                ),
                RedisCoordinatorLeaseTransitionException.Reason.LEASE_CORRUPT
            ),
            new CorruptionCase(
                "noncanonical counter",
                value -> redis.opsForValue().set(
                    value.keyspace().leaseCounterKey(),
                    "01"
                ),
                RedisCoordinatorLeaseTransitionException.Reason.LEASE_CORRUPT
            )
        );

        for (CorruptionCase corruption : cases) {
            clearRedis();
            Fixture fixture = fixture(0, 0, 0);
            corruption.mutation().accept(fixture);
            DatasetState before = state(fixture);
            try (RedisStandaloneCoordinatorLeaseManager manager =
                     new RedisStandaloneCoordinatorLeaseManager(sourceFactory)) {
                assertTransitionFailure(
                    () -> manager.tryAcquire(fixture.primary(), Duration.ofSeconds(2)),
                    corruption.reason()
                );
            }
            assertThat(state(fixture)).as(corruption.name()).isEqualTo(before);
        }
    }

    @Test
    void exactControlChangePoisonsRenewalWithoutLeaseMutation() {
        Fixture fixture = fixture(0, 0, 0);
        try (RedisStandaloneCoordinatorLeaseManager manager =
                 new RedisStandaloneCoordinatorLeaseManager(sourceFactory)) {
            RedisStandaloneCoordinatorLeaseSession session = manager.tryAcquire(
                fixture.primary(),
                Duration.ofSeconds(2)
            ).orElseThrow();
            redis.opsForHash().put(
                fixture.keyspace().controlKey(),
                RedisControlEnvelopeCodec.FIELD_STATE,
                RedisControlState.UPDATING.name()
            );
            DatasetState before = state(fixture);

            assertTransitionFailure(
                session::renew,
                RedisCoordinatorLeaseTransitionException.Reason.CONTROL_CHANGED
            );
            assertThat(session.isPoisoned()).isTrue();
            assertThat(state(fixture)).isEqualTo(before);
        }
    }

    @Test
    void crossDatasetControlCannotRedirectAcquireIntoAnotherDatasetKeyspace() {
        Fixture tenantB = fixture("tenant-b", 0, 0, 0);
        RedisDatasetKeyspace requestedTenantA = new RedisDatasetKeyspace("tenant-a");
        redis.opsForHash().putAll(
            requestedTenantA.controlKey(),
            controlCodec.encode(tenantB.control())
        );
        DatasetState tenantABefore = state(requestedTenantA);
        DatasetState tenantBBefore = state(tenantB);

        assertThatThrownBy(() -> new StrictRedisProtocolClient(sourceFactory)
            .readPrimarySnapshot(requestedTenantA))
            .isInstanceOf(RedisControlCorruptionException.class)
            .hasMessageContaining("control dataset identity")
            .hasMessageContaining("requested keyspace");

        RedisPrimarySnapshot poisoned = new RedisPrimarySnapshot(
            requestedTenantA,
            tenantB.primary().observation(),
            tenantB.control()
        );
        try (RedisStandaloneCoordinatorLeaseManager manager =
                 new RedisStandaloneCoordinatorLeaseManager(sourceFactory)) {
            assertTransitionFailure(
                () -> manager.tryAcquire(poisoned, Duration.ofSeconds(2)),
                RedisCoordinatorLeaseTransitionException.Reason.CONTROL_CHANGED
            );
        }

        assertThat(state(requestedTenantA)).isEqualTo(tenantABefore);
        assertThat(state(tenantB)).isEqualTo(tenantBBefore);
    }

    @Test
    void liveTopologyConfigurationDriftIsRejectedBeforeMutation() {
        Fixture fixture = fixture(0, 0, 0);
        DatasetState before = state(fixture);
        try {
            assertThat(setMaxmemoryPolicy("allkeys-lru")).isEqualTo("OK");
            try (RedisStandaloneCoordinatorLeaseManager manager =
                     new RedisStandaloneCoordinatorLeaseManager(sourceFactory)) {
                assertTransitionFailure(
                    () -> manager.tryAcquire(fixture.primary(), Duration.ofSeconds(2)),
                    RedisCoordinatorLeaseTransitionException.Reason.TOPOLOGY_CHANGED
                );
            }
            assertThat(state(fixture)).isEqualTo(before);
        } finally {
            assertThat(setMaxmemoryPolicy("noeviction")).isEqualTo("OK");
        }
    }

    @Test
    void killedLeaseConnectionPoisonsSessionWithoutReconnectOrMutation() {
        Fixture fixture = fixture(0, 0, 0);
        try (RedisStandaloneCoordinatorLeaseManager manager =
                 new RedisStandaloneCoordinatorLeaseManager(sourceFactory)) {
            RedisStandaloneCoordinatorLeaseSession session = manager.tryAcquire(
                fixture.primary(),
                Duration.ofSeconds(5)
            ).orElseThrow();
            long killedClientId = session.clientId();
            DatasetState before = state(fixture);

            killClient(killedClientId);
            Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(25))
                .until(session::isPoisoned);

            assertThatThrownBy(session::renew)
                .isInstanceOf(RedisProtocolUnavailableException.class)
                .hasMessageContaining("poisoned");
            assertThat(session.isPoisoned()).isTrue();
            assertThat(state(fixture)).isEqualTo(before);
        }
    }

    private Fixture fixture(long revision, long sequence, long counter) {
        return fixture("tenant-a", revision, sequence, counter);
    }

    private Fixture fixture(
        String datasetId,
        long revision,
        long sequence,
        long counter
    ) {
        RedisDatasetKeyspace keyspace = new RedisDatasetKeyspace(datasetId);
        RedisPrimarySnapshot absent = new StrictRedisProtocolClient(sourceFactory)
            .readPrimarySnapshot(keyspace);
        SecurityDatasetIdentity identity = new SecurityDatasetIdentity(datasetId, 1);
        RedisControlEnvelope control = new RedisControlEnvelope(
            identity,
            new RedisIncarnation(absent.observation().runId(), STORAGE_UUID),
            7,
            null,
            RedisControlState.INITIALIZING
        );
        redis.opsForHash().putAll(keyspace.controlKey(), controlCodec.encode(control));

        RedisCoordinatorLease.Unverified candidate = RedisCoordinatorLease.unverified(
            identity,
            keyspace.datasetHash(),
            control.getIncarnation(),
            control.getCounter(),
            RedisCoordinatorLease.State.FREE,
            revision,
            sequence,
            null,
            null,
            0,
            0
        );
        RedisCoordinatorLease.Verified free = RedisCoordinatorLease.verifyFreeForContext(
            candidate,
            identity,
            keyspace.datasetHash(),
            control.getIncarnation(),
            control.getCounter(),
            revision,
            sequence
        );
        redis.opsForHash().putAll(keyspace.leaseKey(), leaseCodec.encode(free));
        redis.opsForValue().set(keyspace.leaseCounterKey(), Long.toString(counter));

        RedisPrimarySnapshot primary = new StrictRedisProtocolClient(sourceFactory)
            .readPrimarySnapshot(keyspace);
        return new Fixture(keyspace, control, primary);
    }

    private RedisCoordinatorLease.Unverified readLease(Fixture fixture) {
        return leaseCodec.decode(hash(fixture.keyspace().leaseKey()));
    }

    private static Attempt contend(
        RedisPrimarySnapshot primary,
        CountDownLatch ready,
        CountDownLatch start,
        CountDownLatch attempted,
        CountDownLatch finish
    ) throws Exception {
        try (RedisStandaloneCoordinatorLeaseManager manager =
                 new RedisStandaloneCoordinatorLeaseManager(sourceFactory)) {
            ready.countDown();
            start.await();
            Optional<RedisStandaloneCoordinatorLeaseSession> candidate;
            try {
                candidate = manager.tryAcquire(primary, Duration.ofSeconds(5));
            } finally {
                attempted.countDown();
            }
            finish.await();
            if (candidate.isEmpty()) {
                return new Attempt(false, 0);
            }
            RedisStandaloneCoordinatorLeaseSession session = candidate.orElseThrow();
            long sequence = session.lease().fencingSequence();
            session.release();
            return new Attempt(true, sequence);
        }
    }

    private static void assertPersistentMetadata(Fixture fixture, String counter) {
        assertThat(redis.opsForValue().get(fixture.keyspace().leaseCounterKey()))
            .isEqualTo(counter);
        assertThat(pTtl(fixture.keyspace().controlKey())).isEqualTo(-1);
        assertThat(pTtl(fixture.keyspace().leaseKey())).isEqualTo(-1);
        assertThat(pTtl(fixture.keyspace().leaseCounterKey())).isEqualTo(-1);
    }

    private static void assertTransitionFailure(
        Runnable action,
        RedisCoordinatorLeaseTransitionException.Reason reason
    ) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(
            RedisCoordinatorLeaseTransitionException.class,
            failure -> {
                assertThat(failure.reason()).isEqualTo(reason);
                assertThat(failure.getMessage()).isEqualTo(
                    RedisCoordinatorLeaseTransitionException.DIAGNOSTIC_CODE + ":" +
                        reason.name()
                );
            }
        );
    }

    private static long redisTimeMillis() {
        try (RedisConnection connection = sourceFactory.getConnection()) {
            Object result = connection.scriptingCommands().eval(
                REDIS_TIME_MILLIS,
                ReturnType.INTEGER,
                0
            );
            if (!(result instanceof Long value)) {
                throw new IllegalStateException("Redis TIME script returned no integer");
            }
            return value;
        }
    }

    private static void killClient(long clientId) {
        try (RedisConnection connection = sourceFactory.getConnection()) {
            nativeCommands(connection).clientKill(new KillArgs().id(clientId).skipme(false));
        }
    }

    private static String setMaxmemoryPolicy(String policy) {
        try (RedisConnection connection = sourceFactory.getConnection()) {
            return nativeCommands(connection).configSet("maxmemory-policy", policy);
        }
    }

    private static long pTtl(String key) {
        try (RedisConnection connection = sourceFactory.getConnection()) {
            return nativeCommands(connection).pttl(ascii(key));
        }
    }

    private static RedisCommands<byte[], byte[]> nativeCommands(
        RedisConnection connection
    ) {
        Object rawCommands = connection.getNativeConnection();
        if (!(rawCommands instanceof RedisAsyncCommands<?, ?> untypedCommands)) {
            throw new IllegalStateException(
                "Lettuce connection did not expose standalone native commands"
            );
        }
        @SuppressWarnings("unchecked")
        RedisAsyncCommands<byte[], byte[]> commands =
            (RedisAsyncCommands<byte[], byte[]>) untypedCommands;
        return commands.getStatefulConnection().sync();
    }

    private static void replaceWithString(String key, String value) {
        redis.delete(key);
        redis.opsForValue().set(key, value);
    }

    private static DatasetState state(Fixture fixture) {
        return state(fixture.keyspace());
    }

    private static DatasetState state(RedisDatasetKeyspace keyspace) {
        return new DatasetState(
            keyState(keyspace.controlKey()),
            keyState(keyspace.leaseKey()),
            keyState(keyspace.leaseCounterKey())
        );
    }

    private static KeyState keyState(String key) {
        DataType type = redis.type(key);
        Object value;
        if (type == DataType.HASH) {
            value = hash(key);
        } else if (type == DataType.STRING) {
            value = redis.opsForValue().get(key);
        } else {
            value = null;
        }
        long ttl = pTtl(key);
        TtlClass ttlClass = ttl == -2
            ? TtlClass.MISSING
            : ttl == -1 ? TtlClass.PERSISTENT : TtlClass.EXPIRING;
        return new KeyState(type, value, ttlClass);
    }

    private static Map<String, String> hash(String key) {
        Map<Object, Object> raw = redis.opsForHash().entries(key);
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<Object, Object> entry : raw.entrySet()) {
            result.put((String) entry.getKey(), (String) entry.getValue());
        }
        return result;
    }

    private static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    private record Fixture(
        RedisDatasetKeyspace keyspace,
        RedisControlEnvelope control,
        RedisPrimarySnapshot primary
    ) {
    }

    private record Attempt(boolean acquired, long fencingSequence) {
    }

    private record CorruptionCase(
        String name,
        Consumer<Fixture> mutation,
        RedisCoordinatorLeaseTransitionException.Reason reason
    ) {
    }

    private record DatasetState(
        KeyState control,
        KeyState lease,
        KeyState counter
    ) {
    }

    private record KeyState(DataType type, Object value, TtlClass ttlClass) {
    }

    private enum TtlClass {
        MISSING,
        PERSISTENT,
        EXPIRING
    }
}
