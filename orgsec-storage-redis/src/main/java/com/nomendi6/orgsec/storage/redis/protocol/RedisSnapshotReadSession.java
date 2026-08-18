package com.nomendi6.orgsec.storage.redis.protocol;

import com.nomendi6.orgsec.storage.redis.bootstrap.RedisSnapshotFamily;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Owner-thread session that proves all six snapshot families were read before adoption.
 *
 * <p>The caller cannot supply offsets, a manifest handle, or a precomputed content digest. Every
 * accepted page is immediately streamed into its family accumulator. Any transport, ordering,
 * lifecycle, or cross-thread failure permanently poisons the session.</p>
 */
final class RedisSnapshotReadSession {

    private static final List<RedisSnapshotFamily> REQUIRED_FAMILIES = List.of(
        RedisSnapshotFamily.PERSONS,
        RedisSnapshotFamily.ORGANIZATIONS,
        RedisSnapshotFamily.PARTY_ROLES,
        RedisSnapshotFamily.POSITION_ROLES,
        RedisSnapshotFamily.ROLES,
        RedisSnapshotFamily.PRIVILEGES
    );
    private static final Set<RedisSnapshotFamily> REQUIRED_FAMILY_SET = Set.copyOf(
        REQUIRED_FAMILIES
    );

    private final Thread ownerThread;
    private final RedisSnapshotReadHandle handle;
    private final RedisCanonicalSnapshotEntryVerifier entryVerifier;
    private final PageOperation pageOperation;
    private final FinishOperation finishOperation;
    private final long maxAccountedBytes;
    private final Map<RedisSnapshotFamily, Long> nextOffsets = new EnumMap<>(
        RedisSnapshotFamily.class
    );
    private final Map<RedisSnapshotFamily, RedisSnapshotFamilyAccumulator> accumulators =
        new EnumMap<>(RedisSnapshotFamily.class);
    private final Map<RedisSnapshotFamily, RedisSnapshotFamilyDigest> completedDigests =
        new EnumMap<>(RedisSnapshotFamily.class);
    private final Set<RedisSnapshotFamily> completedFamilies = EnumSet.noneOf(
        RedisSnapshotFamily.class
    );

    private volatile State state = State.OPEN;
    private long actualAccountedBytes;

    RedisSnapshotReadSession(
        RedisSnapshotReadHandle handle,
        RedisCanonicalSnapshotEntryVerifier entryVerifier,
        PageOperation pageOperation,
        FinishOperation finishOperation,
        long maxAccountedBytes
    ) {
        this.ownerThread = Thread.currentThread();
        this.handle = Objects.requireNonNull(handle, "handle must not be null");
        this.entryVerifier = Objects.requireNonNull(
            entryVerifier,
            "entryVerifier must not be null"
        );
        this.pageOperation = Objects.requireNonNull(
            pageOperation,
            "pageOperation must not be null"
        );
        this.finishOperation = Objects.requireNonNull(
            finishOperation,
            "finishOperation must not be null"
        );
        if (maxAccountedBytes <= 0) {
            throw new IllegalArgumentException("maxAccountedBytes must be positive");
        }
        this.maxAccountedBytes = maxAccountedBytes;
        RedisDatasetKeyspace keyspace = new RedisDatasetKeyspace(
            handle.generation().identity().getSecurityDatasetId()
        );
        for (RedisSnapshotFamily family : REQUIRED_FAMILIES) {
            nextOffsets.put(family, 0L);
            accumulators.put(
                family,
                new RedisSnapshotFamilyAccumulator(
                    familyCode(family),
                    keyspace.familyKey(handle.requestedSnapshotId(), family),
                    keyspace.familyIndexKey(handle.requestedSnapshotId(), family)
                )
            );
        }
    }

    /**
     * Reads and immediately accounts the next ordinal page of one required family.
     *
     * @param family one explicit protocol-v1 family
     * @return exact page accepted into the session accumulator
     */
    synchronized RedisSnapshotPage readNextPage(RedisSnapshotFamily family) {
        requireOwnerAndOpen();
        try {
            Objects.requireNonNull(family, "family must not be null");
            if (!REQUIRED_FAMILY_SET.contains(family)) {
                throw new IllegalArgumentException("family is not required by protocol v1");
            }
            if (completedFamilies.contains(family)) {
                throw new IllegalStateException("snapshot family is already complete");
            }
            long offset = nextOffsets.get(family);
            RedisSnapshotPage page = Objects.requireNonNull(
                pageOperation.read(handle, family, offset),
                "page operation returned no page"
            );
            if (page.family() != family || page.offset() != offset) {
                throw new IllegalStateException("page does not match the requested family cursor");
            }

            RedisSnapshotFamilyAccumulator accumulator = accumulators.get(family);
            for (RedisCanonicalEntry entry : page.entries()) {
                try {
                    entryVerifier.verify(family, entry);
                } catch (RedisSnapshotEntryCorruptionException failure) {
                    throw new RedisSnapshotReadException(
                        RedisSnapshotReadException.Reason.PAGE_ENTRY_INVALID
                    );
                }
                addWithinSnapshotBudget(accumulator, entry);
            }
            nextOffsets.put(family, page.nextOffset());
            if (page.done()) {
                completedDigests.put(family, accumulator.finish());
                completedFamilies.add(family);
            }
            return page;
        } catch (RuntimeException | Error failure) {
            state = State.POISONED;
            throw failure;
        }
    }

    private void addWithinSnapshotBudget(
        RedisSnapshotFamilyAccumulator accumulator,
        RedisCanonicalEntry entry
    ) {
        long previousFamilyBytes = accumulator.currentLogicalBytes();
        try {
            accumulator.add(entry);
            long familyDelta = Math.subtractExact(
                accumulator.currentLogicalBytes(),
                previousFamilyBytes
            );
            actualAccountedBytes = Math.addExact(actualAccountedBytes, familyDelta);
        } catch (ArithmeticException overflow) {
            throw new RedisSnapshotReadException(
                RedisSnapshotReadException.Reason.SNAPSHOT_LIMIT_EXCEEDED
            );
        }
        if (actualAccountedBytes > maxAccountedBytes) {
            throw new RedisSnapshotReadException(
                RedisSnapshotReadException.Reason.SNAPSHOT_LIMIT_EXCEEDED
            );
        }
    }

    /**
     * Recomputes content exclusively from the six completed session accumulators and adopts it.
     *
     * @return contextually verified snapshot view
     */
    synchronized RedisVerifiedSnapshotView finish() {
        requireOwnerAndOpen();
        try {
            if (!completedFamilies.equals(REQUIRED_FAMILY_SET)
                || completedDigests.size() != REQUIRED_FAMILIES.size()) {
                throw new IllegalStateException(
                    "all six required snapshot families must be complete"
                );
            }
            RedisSnapshotContentDigest content = new RedisSnapshotContentDigest(
                completedDigests.get(RedisSnapshotFamily.PERSONS),
                completedDigests.get(RedisSnapshotFamily.ORGANIZATIONS),
                completedDigests.get(RedisSnapshotFamily.PARTY_ROLES),
                completedDigests.get(RedisSnapshotFamily.POSITION_ROLES),
                completedDigests.get(RedisSnapshotFamily.ROLES),
                completedDigests.get(RedisSnapshotFamily.PRIVILEGES)
            );
            RedisVerifiedSnapshotView view = Objects.requireNonNull(
                finishOperation.finish(handle, content),
                "finish operation returned no verified view"
            );
            state = State.FINISHED;
            return view;
        } catch (RuntimeException | Error failure) {
            state = State.POISONED;
            throw failure;
        }
    }

    private void requireOwnerAndOpen() {
        if (Thread.currentThread() != ownerThread) {
            state = State.POISONED;
            throw new IllegalStateException("snapshot read session is bound to its owner thread");
        }
        if (state != State.OPEN) {
            throw new IllegalStateException("snapshot read session is not open");
        }
    }

    private static RedisSnapshotFamilyCode familyCode(RedisSnapshotFamily family) {
        return switch (family) {
            case PERSONS -> RedisSnapshotFamilyCode.PERSONS;
            case ORGANIZATIONS -> RedisSnapshotFamilyCode.ORGANIZATIONS;
            case PARTY_ROLES -> RedisSnapshotFamilyCode.PARTY_ROLES;
            case POSITION_ROLES -> RedisSnapshotFamilyCode.POSITION_ROLES;
            case ROLES -> RedisSnapshotFamilyCode.ROLES;
            case PRIVILEGES -> RedisSnapshotFamilyCode.PRIVILEGES;
        };
    }

    @FunctionalInterface
    interface PageOperation {

        RedisSnapshotPage read(
            RedisSnapshotReadHandle handle,
            RedisSnapshotFamily family,
            long offset
        );
    }

    @FunctionalInterface
    interface FinishOperation {

        RedisVerifiedSnapshotView finish(
            RedisSnapshotReadHandle handle,
            RedisSnapshotContentDigest content
        );
    }

    private enum State {
        OPEN,
        POISONED,
        FINISHED
    }
}
