package com.nomendi6.orgsec.storage.redis.protocol;

import com.nomendi6.orgsec.fence.SecurityDatasetIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

@Testcontainers
class StrictRedisBootstrapTripletProbeCompatibilityIntegrationTest {

    private static final UUID STORAGE_UUID =
        UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private static final UUID SNAPSHOT_ID =
        UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");

    @Container
    private static final GenericContainer<?> REDIS_SIX = redis("redis:6.0-alpine");

    @Container
    private static final GenericContainer<?> REDIS_EIGHT = redis("redis:8.4.0-alpine");

    @Test
    void observesClassifiesAndInitializesRealRedisSixTriplets() throws Exception {
        exercise(REDIS_SIX);
    }

    @Test
    void observesClassifiesAndInitializesRealRedisEightTriplets() throws Exception {
        exercise(REDIS_EIGHT);
    }

    private static void exercise(GenericContainer<?> redis) throws Exception {
        LettuceConnectionFactory factory = connectionFactory(redis);
        RedisTemplate<String, String> template = template(factory);
        RedisDatasetKeyspace requested = new RedisDatasetKeyspace("tenant-a");
        StrictRedisBootstrapTripletProbe probe = new StrictRedisBootstrapTripletProbe(factory);
        try {
            clear(factory);
            RedisBootstrapTripletObservation absent = probe.readTriplet(requested);
            assertThat(RedisBootstrapTripletClassifier.assess(absent).kind()).isEqualTo(
                RedisBootstrapTripletAssessment.Kind.TRIPLET_ABSENT
            );

            String currentRunId = absent.primary().runId();
            putTriplet(template, requested, "tenant-a", currentRunId, 2, 5);
            RedisBootstrapTripletObservation coherent = probe.readTriplet(requested);
            assertThat(coherent.leaseCounter()).hasValue(5);
            assertThat(coherent.lease().orElseThrow().fencingSequence()).isEqualTo(2);
            assertThat(RedisBootstrapTripletClassifier.assess(coherent).kind()).isEqualTo(
                RedisBootstrapTripletAssessment.Kind.COHERENT_SAME_RUN_ID
            );

            assertThat(template.expire(requested.leaseKey(), Duration.ofMinutes(5))).isTrue();
            assertCorruption(
                () -> probe.readTriplet(requested),
                RedisBootstrapTripletCorruptionException.Reason.LEASE_TTL_INVALID
            );

            clear(factory);
            template.opsForValue().set(requested.controlKey(), "wrong-type-sentinel");
            assertCorruption(
                () -> probe.readTriplet(requested),
                RedisBootstrapTripletCorruptionException.Reason.CONTROL_TYPE_INVALID
            );

            clear(factory);
            Map<String, String> oversizedControl = new java.util.LinkedHashMap<>(
                new RedisControlEnvelopeCodec().encode(control("tenant-a", currentRunId))
            );
            oversizedControl.put(
                RedisControlEnvelopeCodec.FIELD_DATASET_ID,
                "x".repeat(RedisControlEnvelopeCodec.MAX_DATASET_ID_UTF8_BYTES + 1)
            );
            template.opsForHash().putAll(requested.controlKey(), oversizedControl);
            assertCorruption(
                () -> probe.readTriplet(requested),
                RedisBootstrapTripletCorruptionException.Reason.CONTROL_WIRE_INVALID
            );

            clear(factory);
            putTriplet(template, requested, "tenant-b", currentRunId, 2, 2);
            RedisBootstrapTripletAssessment confusedDeputy =
                RedisBootstrapTripletClassifier.assess(probe.readTriplet(requested));
            assertThat(confusedDeputy.kind()).isEqualTo(
                RedisBootstrapTripletAssessment.Kind.INCOHERENT
            );
            assertThat(confusedDeputy.reason()).contains(
                RedisBootstrapTripletAssessment.Reason.CONTROL_DATASET_MISMATCH
            );

            exerciseInitializer(factory, template, probe, requested);
        } finally {
            factory.destroy();
        }
    }

    private static void exerciseInitializer(
        LettuceConnectionFactory factory,
        RedisTemplate<String, String> template,
        StrictRedisBootstrapTripletProbe probe,
        RedisDatasetKeyspace keyspace
    ) throws Exception {
        SecurityDatasetIdentity identity = new SecurityDatasetIdentity(
            keyspace.securityDatasetId(),
            1
        );
        StrictRedisBootstrapInitializer initializer =
            new StrictRedisBootstrapInitializer(factory);

        clear(factory);
        RedisPrimarySnapshot expectedAbsent = expectedPrimary(probe.readTriplet(keyspace));
        List<StrictRedisBootstrapInitializer.Outcome> outcomes = raceInitializers(
            initializer,
            identity,
            expectedAbsent
        );
        assertThat(outcomes).filteredOn(
            outcome -> outcome == StrictRedisBootstrapInitializer.Outcome.INITIALIZED
        ).hasSize(1);
        assertThat(outcomes).filteredOn(
            outcome -> outcome ==
                StrictRedisBootstrapInitializer.Outcome.PRESENT_REQUIRES_VALIDATION
        ).hasSize(outcomes.size() - 1);

        RedisBootstrapTripletObservation initialized = probe.readTriplet(keyspace);
        assertThat(RedisBootstrapTripletClassifier.assess(initialized).kind()).isEqualTo(
            RedisBootstrapTripletAssessment.Kind.COHERENT_SAME_RUN_ID
        );
        RedisControlEnvelope initializedControl = initialized.control().orElseThrow();
        RedisCoordinatorLease.Unverified initializedLease = initialized.lease().orElseThrow();
        assertThat(initializedControl.getIdentity()).isEqualTo(identity);
        assertThat(initializedControl.getState()).isEqualTo(RedisControlState.INITIALIZING);
        assertThat(initializedControl.getCounter()).isZero();
        assertThat(initializedControl.getActiveSnapshotId()).isNull();
        assertThat(initializedControl.getIncarnation().getPrimaryRunId()).isEqualTo(
            initialized.primary().runId()
        );
        assertThat(initializedControl.getIncarnation().getStorageUuid().version()).isEqualTo(4);
        assertThat(initializedControl.getIncarnation().getStorageUuid().variant()).isEqualTo(2);
        RedisCoordinatorLease.Verified freeLease =
            RedisCoordinatorLease.verifyFreeForContext(
                initializedLease,
                identity,
                keyspace.datasetHash(),
                initializedControl.getIncarnation(),
                0,
                0,
                0
            );
        assertThat(freeLease.state()).isEqualTo(RedisCoordinatorLease.State.FREE);
        assertThat(initialized.leaseCounter()).hasValue(0);
        assertThat(template.opsForHash().size(keyspace.controlKey())).isEqualTo(
            RedisControlEnvelopeCodec.REQUIRED_FIELD_COUNT
        );
        assertThat(template.opsForHash().size(keyspace.leaseKey())).isEqualTo(
            RedisCoordinatorLeaseCodec.REQUIRED_FIELD_COUNT
        );
        assertPersistent(template, keyspace.controlKey());
        assertPersistent(template, keyspace.leaseKey());
        assertPersistent(template, keyspace.leaseCounterKey());

        Map<Object, Object> originalControl = new HashMap<>(
            template.opsForHash().entries(keyspace.controlKey())
        );
        Map<Object, Object> originalLease = new HashMap<>(
            template.opsForHash().entries(keyspace.leaseKey())
        );
        String originalCounter = template.opsForValue().get(keyspace.leaseCounterKey());
        assertThat(initializer.initialize(identity, expectedAbsent)).isEqualTo(
            StrictRedisBootstrapInitializer.Outcome.PRESENT_REQUIRES_VALIDATION
        );
        assertThat(template.opsForHash().entries(keyspace.controlKey()))
            .isEqualTo(originalControl);
        assertThat(template.opsForHash().entries(keyspace.leaseKey())).isEqualTo(originalLease);
        assertThat(template.opsForValue().get(keyspace.leaseCounterKey()))
            .isEqualTo(originalCounter);

        clear(factory);
        RedisPrimarySnapshot partialExpected = expectedPrimary(probe.readTriplet(keyspace));
        template.opsForHash().put(keyspace.controlKey(), "sentinel", "partial");
        assertInitializationFailure(
            () -> initializer.initialize(identity, partialExpected),
            RedisBootstrapInitializationException.Reason.PARTIAL_METADATA
        );
        assertThat(template.opsForHash().entries(keyspace.controlKey()))
            .containsOnly(Map.entry("sentinel", "partial"));
        assertThat(template.hasKey(keyspace.leaseKey())).isFalse();
        assertThat(template.hasKey(keyspace.leaseCounterKey())).isFalse();

        clear(factory);
        RedisPrimarySnapshot typeExpected = expectedPrimary(probe.readTriplet(keyspace));
        template.opsForValue().set(keyspace.controlKey(), "wrong-type");
        assertInitializationFailure(
            () -> initializer.initialize(identity, typeExpected),
            RedisBootstrapInitializationException.Reason.CONTROL_TYPE_INVALID
        );
        assertThat(template.opsForValue().get(keyspace.controlKey())).isEqualTo("wrong-type");

        clear(factory);
        RedisPrimarySnapshot ttlExpected = expectedPrimary(probe.readTriplet(keyspace));
        template.opsForHash().put(keyspace.controlKey(), "sentinel", "ttl");
        assertThat(template.expire(keyspace.controlKey(), Duration.ofMinutes(5))).isTrue();
        assertInitializationFailure(
            () -> initializer.initialize(identity, ttlExpected),
            RedisBootstrapInitializationException.Reason.CONTROL_TTL_INVALID
        );
        assertThat(template.opsForHash().get(keyspace.controlKey(), "sentinel"))
            .isEqualTo("ttl");
        assertThat(template.getExpire(keyspace.controlKey())).isPositive();

        clear(factory);
        RedisPrimarySnapshot oversizedExpected = expectedPrimary(probe.readTriplet(keyspace));
        assertThat(initializer.initialize(identity, oversizedExpected)).isEqualTo(
            StrictRedisBootstrapInitializer.Outcome.INITIALIZED
        );
        String oversized = "x".repeat(
            RedisControlEnvelopeCodec.MAX_DATASET_ID_UTF8_BYTES + 1
        );
        template.opsForHash().put(
            keyspace.controlKey(),
            RedisControlEnvelopeCodec.FIELD_DATASET_ID,
            oversized
        );
        assertInitializationFailure(
            () -> initializer.initialize(identity, oversizedExpected),
            RedisBootstrapInitializationException.Reason.CONTROL_WIRE_INVALID
        );
        assertThat(template.opsForHash().get(
            keyspace.controlKey(),
            RedisControlEnvelopeCodec.FIELD_DATASET_ID
        )).isEqualTo(oversized);

        clear(factory);
        RedisPrimarySnapshot current = expectedPrimary(probe.readTriplet(keyspace));
        RedisPrimarySnapshot staleRun = new RedisPrimarySnapshot(
            new RedisPrimaryObservation(
                differentRunId(current.observation().runId()),
                "master",
                false,
                "noeviction"
            ),
            null
        ).bindRequestedKeyspace(keyspace);
        assertInitializationFailure(
            () -> initializer.initialize(identity, staleRun),
            RedisBootstrapInitializationException.Reason.TOPOLOGY_CHANGED
        );
        assertTripletAbsent(template, keyspace);

        clear(factory);
        RedisPrimarySnapshot oomExpected = expectedPrimary(probe.readTriplet(keyspace));
        long usedMemory = usedMemory(factory);
        try {
            setMaxmemory(factory, Math.max(1, usedMemory - 1));
            assertInitializationFailure(
                () -> initializer.initialize(identity, oomExpected),
                RedisBootstrapInitializationException.Reason.WRITE_FAILED_ROLLED_BACK
            );
            assertTripletAbsent(template, keyspace);
        } finally {
            setMaxmemory(factory, 0);
            clear(factory);
        }
    }

    private static List<StrictRedisBootstrapInitializer.Outcome> raceInitializers(
        StrictRedisBootstrapInitializer initializer,
        SecurityDatasetIdentity identity,
        RedisPrimarySnapshot expectedAbsent
    ) throws Exception {
        int callerCount = 8;
        ExecutorService executor = Executors.newFixedThreadPool(callerCount);
        CountDownLatch ready = new CountDownLatch(callerCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<StrictRedisBootstrapInitializer.Outcome>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < callerCount; index++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("initializer race did not start");
                    }
                    return initializer.initialize(identity, expectedAbsent);
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<StrictRedisBootstrapInitializer.Outcome> outcomes = new ArrayList<>();
            for (Future<StrictRedisBootstrapInitializer.Outcome> future : futures) {
                outcomes.add(future.get(20, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static RedisPrimarySnapshot expectedPrimary(
        RedisBootstrapTripletObservation observation
    ) {
        return new RedisPrimarySnapshot(
            observation.primary(),
            observation.control().orElse(null)
        ).bindRequestedKeyspace(observation.requestedKeyspace());
    }

    private static String differentRunId(String currentRunId) {
        char replacement = currentRunId.charAt(0) == '0' ? '1' : '0';
        return replacement + currentRunId.substring(1);
    }

    private static void assertPersistent(
        RedisTemplate<String, String> template,
        String key
    ) {
        assertThat(template.getExpire(key)).isEqualTo(-1);
    }

    private static long usedMemory(LettuceConnectionFactory factory) {
        try (RedisConnection connection = factory.getConnection()) {
            String value = connection.serverCommands().info("memory")
                .getProperty("used_memory");
            return Long.parseLong(value);
        }
    }

    private static void setMaxmemory(LettuceConnectionFactory factory, long bytes) {
        try (RedisConnection connection = factory.getConnection()) {
            connection.serverCommands().setConfig("maxmemory", Long.toString(bytes));
        }
    }

    private static void assertTripletAbsent(
        RedisTemplate<String, String> template,
        RedisDatasetKeyspace keyspace
    ) {
        assertThat(template.hasKey(keyspace.controlKey())).isFalse();
        assertThat(template.hasKey(keyspace.leaseKey())).isFalse();
        assertThat(template.hasKey(keyspace.leaseCounterKey())).isFalse();
    }

    private static void assertInitializationFailure(
        Runnable action,
        RedisBootstrapInitializationException.Reason reason
    ) {
        Throwable failure = catchThrowable(action::run);
        assertThat(failure).isExactlyInstanceOf(RedisBootstrapInitializationException.class);
        RedisBootstrapInitializationException initializationFailure =
            (RedisBootstrapInitializationException) failure;
        assertThat(initializationFailure.reason()).isEqualTo(reason);
        assertThat(initializationFailure).hasNoCause();
    }

    private static void putTriplet(
        RedisTemplate<String, String> template,
        RedisDatasetKeyspace targetKeyspace,
        String encodedDatasetId,
        String runId,
        long fencingSequence,
        long counter
    ) {
        RedisDatasetKeyspace encodedKeyspace = new RedisDatasetKeyspace(encodedDatasetId);
        RedisControlEnvelope control = control(encodedDatasetId, runId);
        SecurityDatasetIdentity identity = control.getIdentity();
        RedisIncarnation incarnation = control.getIncarnation();
        RedisCoordinatorLease.Unverified candidate = RedisCoordinatorLease.unverified(
            identity,
            encodedKeyspace.datasetHash(),
            incarnation,
            control.getCounter(),
            RedisCoordinatorLease.State.FREE,
            4,
            fencingSequence,
            null,
            null,
            0,
            0
        );
        RedisCoordinatorLease.Verified lease = RedisCoordinatorLease.verifyFreeForContext(
            candidate,
            identity,
            encodedKeyspace.datasetHash(),
            incarnation,
            control.getCounter(),
            4,
            fencingSequence
        );

        Map<String, String> controlFields = new RedisControlEnvelopeCodec().encode(control);
        Map<String, String> leaseFields = new RedisCoordinatorLeaseCodec().encode(lease);
        template.opsForHash().putAll(targetKeyspace.controlKey(), controlFields);
        template.opsForHash().putAll(targetKeyspace.leaseKey(), leaseFields);
        template.opsForValue().set(targetKeyspace.leaseCounterKey(), Long.toString(counter));
    }

    private static RedisControlEnvelope control(String datasetId, String runId) {
        SecurityDatasetIdentity identity = new SecurityDatasetIdentity(datasetId, 1);
        return new RedisControlEnvelope(
            identity,
            new RedisIncarnation(runId, STORAGE_UUID),
            7,
            SNAPSHOT_ID,
            RedisControlState.READY
        );
    }

    private static LettuceConnectionFactory connectionFactory(GenericContainer<?> redis) {
        RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration();
        configuration.setHostName(redis.getHost());
        configuration.setPort(redis.getMappedPort(6379));
        LettuceConnectionFactory factory = new LettuceConnectionFactory(configuration);
        factory.afterPropertiesSet();
        return factory;
    }

    private static RedisTemplate<String, String> template(LettuceConnectionFactory factory) {
        RedisTemplate<String, String> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);
        StringRedisSerializer serializer = new StringRedisSerializer();
        template.setKeySerializer(serializer);
        template.setValueSerializer(serializer);
        template.setHashKeySerializer(serializer);
        template.setHashValueSerializer(serializer);
        template.afterPropertiesSet();
        return template;
    }

    private static void clear(LettuceConnectionFactory factory) {
        RedisConnection connection = factory.getConnection();
        try {
            connection.serverCommands().flushAll();
        } finally {
            connection.close();
        }
    }

    private static GenericContainer<?> redis(String image) {
        return new GenericContainer<>(DockerImageName.parse(image)).withExposedPorts(6379);
    }

    private static void assertCorruption(
        Runnable action,
        RedisBootstrapTripletCorruptionException.Reason reason
    ) {
        Throwable failure = catchThrowable(action::run);
        assertThat(failure).isExactlyInstanceOf(RedisBootstrapTripletCorruptionException.class);
        RedisBootstrapTripletCorruptionException corruption =
            (RedisBootstrapTripletCorruptionException) failure;
        assertThat(corruption.reason()).isEqualTo(reason);
        assertThat(corruption).hasNoCause();
    }
}
