package com.nomendi6.orgsec.fence;

/**
 * Transaction boundary and exclusive source-database fence for one security dataset.
 *
 * <p>An implementation opens or joins one source-database transaction, acquires the dataset fence
 * as the first database lock, exact-matches it against the expected external identity and keeps the
 * exclusive lock until the callback returns. Reads performed by the callback therefore participate
 * in the same database snapshot. Implementations must roll back and propagate callback failures.</p>
 *
 * <p>This contract deliberately has no dependency on Spring transactions, JDBC or JPA. A generated
 * application adapts its chosen transaction and persistence stack while the library remains usable
 * with any source database implementation.</p>
 */
public interface SecurityDatasetFenceStore {

    /**
     * Executes work while the exact source-database fence is locked.
     *
     * @param expectedIdentity identity from the verified external release fence
     * @param work work executed inside the locked source-database transaction
     * @param <T> callback result type
     * @return callback result
     * @throws RuntimeException when the fence cannot be locked, does not exact-match, or work fails
     */
    <T> T withLockedFence(SecurityDatasetIdentity expectedIdentity, LockedFenceWork<T> work);

    /**
     * Work performed inside the source-database fence transaction.
     *
     * @param <T> result type
     */
    @FunctionalInterface
    interface LockedFenceWork<T> {

        T execute(LockedFence fence);
    }

    /**
     * Operations available while the source-database fence is exclusively locked.
     */
    interface LockedFence {

        /**
         * Returns the current locked fence value.
         */
        SecurityDatasetFence current();

        /**
         * Atomically increments and returns the monotonic security content version.
         */
        SecurityDatasetFence incrementContentVersion();
    }
}
