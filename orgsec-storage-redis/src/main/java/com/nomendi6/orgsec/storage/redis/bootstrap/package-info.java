/**
 * Versioned, coordinator-owned contracts for building a complete Redis authorization snapshot.
 *
 * <p>Applications implement {@link com.nomendi6.orgsec.storage.redis.bootstrap.RedisSnapshotLoader}
 * only. The library owns and supplies the bootstrap session; applications must not implement,
 * retain, publish, or otherwise control that session.</p>
 */
package com.nomendi6.orgsec.storage.redis.bootstrap;
