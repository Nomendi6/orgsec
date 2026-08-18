package com.nomendi6.orgsec.storage.redis.bootstrap;

/**
 * Loads one complete security snapshot through a coordinator-owned bootstrap session.
 *
 * <p>The coordinator invokes a loader while holding the source-database dataset fence and its
 * database snapshot. An implementation must:</p>
 *
 * <ul>
 *   <li>read fresh source data instead of reusing process-wide {@code All*Store} state,</li>
 *   <li>write every data family in batches no larger than the session limit,</li>
 *   <li>explicitly complete every family, including an empty family, and</li>
 *   <li>finish synchronously on the invoking thread without retaining the session.</li>
 * </ul>
 *
 * <p>Returning from this method does not publish the snapshot. It only allows the coordinator to
 * validate and seal the session before the manifest and READY transition.
 * Throwing an exception aborts the bootstrap attempt.</p>
 */
@FunctionalInterface
public interface RedisSnapshotLoader {

    /**
     * Loads a complete snapshot into the supplied session.
     *
     * @param session the coordinator-owned, thread-confined bootstrap session
     */
    void loadSnapshot(RedisBootstrapSession session);
}
