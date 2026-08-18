package com.nomendi6.orgsec.storage.redis.protocol;

import com.nomendi6.orgsec.fence.SecurityDatasetFence;
import com.nomendi6.orgsec.fence.SecurityDatasetFenceStore;
import com.nomendi6.orgsec.fence.SecurityDatasetIdentity;
import com.nomendi6.orgsec.model.OrganizationDef;
import com.nomendi6.orgsec.model.PersonDef;
import com.nomendi6.orgsec.model.PrivilegeDef;
import com.nomendi6.orgsec.model.RoleDef;
import com.nomendi6.orgsec.storage.redis.bootstrap.RedisSnapshotFamily;
import com.nomendi6.orgsec.storage.redis.bootstrap.RedisSnapshotLoader;
import com.nomendi6.orgsec.storage.redis.bootstrap.ValidatingRedisBootstrapSession;
import com.nomendi6.orgsec.storage.redis.config.RedisStorageProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import java.time.Duration;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Library-owned cold-start and refresh coordinator for the standalone Redis snapshot protocol.
 *
 * <p>It is the only component that may acquire a writer lease, invoke the application loader,
 * publish READY and swap the local authorization view. GET/LIST read that view after rechecking
 * the current primary control generation.</p>
 */
public final class RedisSnapshotCoordinator {

    private static final Logger log = LoggerFactory.getLogger(RedisSnapshotCoordinator.class);
    private static final Duration WRITER_LEASE_DURATION = Duration.ofSeconds(60);

    private final RedisStorageProperties properties;
    private final RedisConnectionFactory connectionFactory;
    private final SecurityDatasetFenceStore fenceStore;
    private final RedisSnapshotLoader loader;
    private final AtomicReference<RedisAuthorizationSnapshot> view = new AtomicReference<>();
    private final AtomicReference<Runnable> readinessListener = new AtomicReference<>();

    public RedisSnapshotCoordinator(
        RedisStorageProperties properties,
        RedisConnectionFactory connectionFactory,
        SecurityDatasetFenceStore fenceStore,
        RedisSnapshotLoader loader
    ) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.connectionFactory = Objects.requireNonNull(
            connectionFactory,
            "connectionFactory must not be null"
        );
        this.fenceStore = Objects.requireNonNull(fenceStore, "fenceStore must not be null");
        this.loader = Objects.requireNonNull(loader, "loader must not be null");
    }

    public void onReadinessChanged(Runnable listener) {
        readinessListener.set(Objects.requireNonNull(listener, "listener must not be null"));
    }

    public void bootstrap() {
        try {
            fenceStore.withLockedFence(identity(), this::bootstrapUnderFence);
        } catch (RuntimeException failure) {
            log.error("Redis snapshot bootstrap failed closed", failure);
            clearView();
        }
    }

    public void refresh() {
        bootstrap();
    }

    public boolean isReady() {
        RedisAuthorizationSnapshot current = view.get();
        return current != null && controlMatches(current.generation());
    }

    public PersonDef person(Long personId) {
        RedisAuthorizationSnapshot current = readyView();
        return current == null ? null : current.person(personId);
    }

    public OrganizationDef organization(Long organizationId) {
        RedisAuthorizationSnapshot current = readyView();
        return current == null ? null : current.organization(organizationId);
    }

    public RoleDef partyRole(Long roleId) {
        RedisAuthorizationSnapshot current = readyView();
        return current == null ? null : current.partyRole(roleId);
    }

    public RoleDef positionRole(Long roleId) {
        RedisAuthorizationSnapshot current = readyView();
        return current == null ? null : current.positionRole(roleId);
    }

    public PrivilegeDef privilege(String name) {
        RedisAuthorizationSnapshot current = readyView();
        return current == null ? null : current.privilege(name);
    }

    public Map<Long, PersonDef> persons(Collection<Long> personIds) {
        RedisAuthorizationSnapshot current = readyView();
        return current == null ? Map.of() : current.persons(personIds);
    }

    public Map<Long, OrganizationDef> organizations(Collection<Long> organizationIds) {
        RedisAuthorizationSnapshot current = readyView();
        return current == null ? Map.of() : current.organizations(organizationIds);
    }

    private Void bootstrapUnderFence(SecurityDatasetFenceStore.LockedFence locked) {
        SecurityDatasetFence fence = locked.current();
        RedisDatasetKeyspace keyspace = new RedisDatasetKeyspace(
            fence.getIdentity().getSecurityDatasetId()
        );
        StrictRedisProtocolClient protocolClient = new StrictRedisProtocolClient(connectionFactory);
        RedisPrimarySnapshot primary = ensureMetadata(protocolClient, keyspace, fence.getIdentity());
        RedisControlEnvelope control = primary.controlEnvelope().orElse(null);
        if (control != null
            && control.getState() == RedisControlState.READY
            && adoptReady(protocolClient, primary, fence)) {
            return null;
        }
        publishFreshSnapshot(primary, fence, keyspace);
        return null;
    }

    private RedisPrimarySnapshot ensureMetadata(
        StrictRedisProtocolClient protocolClient,
        RedisDatasetKeyspace keyspace,
        SecurityDatasetIdentity identity
    ) {
        RedisPrimarySnapshot primary = protocolClient.readPrimarySnapshot(keyspace);
        if (primary.controlEnvelope().isPresent()) {
            return primary;
        }
        new StrictRedisBootstrapInitializer(connectionFactory).initialize(identity, primary);
        return protocolClient.readPrimarySnapshot(keyspace);
    }

    private boolean adoptReady(
        StrictRedisProtocolClient protocolClient,
        RedisPrimarySnapshot primary,
        SecurityDatasetFence fence
    ) {
        try {
            RedisSnapshotGeneration generation = RedisSnapshotGeneration.from(
                primary,
                fence.getIdentity()
            );
            RedisAuthorizationSnapshot loaded = readView(generation, fence);
            if (loaded.manifest().sourceFence().getSecurityContentVersion()
                != fence.getSecurityContentVersion()) {
                return false;
            }
            install(loaded);
            return true;
        } catch (RuntimeException failure) {
            log.warn("Ready snapshot could not be adopted; a writer refresh is required", failure);
            return false;
        }
    }

    private void publishFreshSnapshot(
        RedisPrimarySnapshot primary,
        SecurityDatasetFence fence,
        RedisDatasetKeyspace keyspace
    ) {
        if (primary.controlEnvelope().isEmpty()) {
            throw new RedisProtocolUnavailableException(
                "Redis control metadata is absent after initialization."
            );
        }
        RedisStandaloneCoordinatorLeaseManager leases =
            new RedisStandaloneCoordinatorLeaseManager(connectionFactory);
        try {
            Optional<RedisStandaloneCoordinatorLeaseSession> acquired =
                leases.tryAcquire(primary, WRITER_LEASE_DURATION);
            if (acquired.isEmpty()) {
                log.info("Another instance holds the Redis writer lease; remaining NOT_READY");
                return;
            }
            try (RedisStandaloneCoordinatorLeaseSession leaseSession = acquired.get()) {
                RedisCoordinatorLease.Verified lease = leaseSession.lease();
                StrictRedisSnapshotWriter writer = new StrictRedisSnapshotWriter(
                    connectionFactory,
                    writeLimits()
                );
                try (RedisStagingSession staging = writer.begin(primary, fence, lease)) {
                    RedisCanonicalSnapshotPayloadCodec codec =
                        new RedisCanonicalSnapshotPayloadCodec();
                    RedisSnapshotStagingSink sink = new RedisSnapshotStagingSink(
                        staging,
                        keyspace,
                        staging.snapshotId(),
                        codec
                    );
                    ValidatingRedisBootstrapSession session = new ValidatingRedisBootstrapSession(
                        fence,
                        Math.max(1, properties.getPreload().getBatchSize()),
                        sink
                    );
                    loader.loadSnapshot(session);
                    session.seal();
                    sink.completeAllFamilies();
                    RedisSnapshotManifest.Verified manifest =
                        staging.sealAndWriteManifest(sink.content());
                    RedisControlEnvelope published = new RedisSnapshotPublisher(connectionFactory)
                        .publishReady(primary, lease, manifest);
                    RedisPrimarySnapshot readyPrimary = new RedisPrimarySnapshot(
                        keyspace,
                        primary.observation(),
                        published
                    );
                    install(readView(
                        RedisSnapshotGeneration.from(readyPrimary, fence.getIdentity()),
                        fence
                    ));
                }
            }
        } finally {
            leases.close();
        }
    }

    private RedisAuthorizationSnapshot readView(
        RedisSnapshotGeneration generation,
        SecurityDatasetFence fence
    ) {
        StrictRedisSnapshotReader reader = new StrictRedisSnapshotReader(
            connectionFactory,
            RedisSnapshotReadLimits.scaffoldDefaults()
        );
        RedisSnapshotReadSession session = reader.openActive(generation, fence);
        Map<RedisSnapshotFamily, Map<byte[], byte[]>> payloads =
            new EnumMap<>(RedisSnapshotFamily.class);
        for (RedisSnapshotFamily family : RedisSnapshotFamily.values()) {
            Map<byte[], byte[]> familyPayloads = new LinkedHashMap<>();
            while (true) {
                RedisSnapshotPage page = session.readNextPage(family);
                for (RedisCanonicalEntry entry : page.entries()) {
                    familyPayloads.put(entry.canonicalKey(), entry.canonicalPayload());
                }
                if (page.done()) {
                    break;
                }
            }
            payloads.put(family, familyPayloads);
        }
        return new RedisAuthorizationSnapshot(session.finish(), payloads);
    }

    private RedisAuthorizationSnapshot readyView() {
        RedisAuthorizationSnapshot current = view.get();
        if (current == null) {
            return null;
        }
        if (controlMatches(current.generation())) {
            return current;
        }
        log.warn("Local Redis authorization view is no longer the READY generation");
        clearView();
        return null;
    }

    private boolean controlMatches(RedisSnapshotGeneration generation) {
        try {
            RedisDatasetKeyspace keyspace = new RedisDatasetKeyspace(
                generation.identity().getSecurityDatasetId()
            );
            RedisPrimarySnapshot latest = new StrictRedisProtocolClient(connectionFactory)
                .readPrimarySnapshot(keyspace);
            RedisSnapshotGeneration observed = RedisSnapshotGeneration.from(
                latest,
                generation.identity()
            );
            return observed.equals(generation);
        } catch (RuntimeException failure) {
            log.debug("Redis control recheck failed closed", failure);
            return false;
        }
    }

    private void install(RedisAuthorizationSnapshot snapshot) {
        view.set(snapshot);
        notifyReadiness();
        log.info(
            "Installed Redis authorization snapshot {} generation {}",
            snapshot.manifest().snapshotId(),
            snapshot.generation().counter()
        );
    }

    private void clearView() {
        view.set(null);
        notifyReadiness();
    }

    private void notifyReadiness() {
        Runnable listener = readinessListener.get();
        if (listener != null) {
            listener.run();
        }
    }

    private SecurityDatasetIdentity identity() {
        return new SecurityDatasetIdentity(
            properties.getSecurityDatasetId(),
            RedisWireProtocol.VERSION_ONE
        );
    }

    private RedisSnapshotWriteLimits writeLimits() {
        int batchSize = Math.max(1, properties.getPreload().getBatchSize());
        return new RedisSnapshotWriteLimits(
            batchSize,
            4096,
            RedisCanonicalSnapshotPayloadCodec.MAX_PERSON_PAYLOAD_BYTES,
            8L * 1024L * 1024L
        );
    }
}
