package com.nomendi6.orgsec.fence;

/**
 * Transaction boundary and exclusive source-database fence for one security dataset.
 *
 * <p>An implementation owns a fresh source-database transaction and acquires the dataset fence as
 * its first database operation. Joining an existing transaction is permitted only when that same
 * fence was already acquired through this contract as the transaction's first database operation;
 * otherwise the call must fail closed. The store exact-matches the locked row against the expected
 * locally configured dataset identity and keeps the exclusive lock until the callback and
 * transaction complete. Reads performed by the callback therefore participate in the same
 * database snapshot.
 * Implementations must commit before a successful return and roll back and propagate callback
 * failures.</p>
 *
 * <p>Every transaction that mutates security source data must use this lock order before any
 * domain-data lock and must call {@link LockedFence#incrementContentVersion()} exactly once in the
 * same transaction. Bootstrap and read-only recovery work do not increment the version. This is a
 * global writer contract: bypassing it makes a published authorization snapshot unsafe.</p>
 *
 * <p>This contract deliberately has no dependency on Spring transactions, JDBC or JPA. A generated
 * application adapts its chosen transaction and persistence stack while the library remains usable
 * with any source database implementation.</p>
 */
public interface SecurityDatasetFenceStore {

    /**
     * Executes work while the exact source-database fence is locked.
     *
     * @param expectedIdentity locally configured identity expected in the source-database fence
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
     *
     * <p>A locked handle is synchronous, owner-thread confined, and valid only for the dynamic
     * extent of its {@link LockedFenceWork} callback. Implementations must reject retained,
     * deferred, asynchronous, or cross-thread use.</p>
     */
    interface LockedFence {

        /**
         * Returns the current locked fence value.
         */
        SecurityDatasetFence current();

        /**
         * Atomically increments and returns the monotonic security content version.
         *
         * @throws ArithmeticException rather than wrapping at {@link Long#MAX_VALUE}
         */
        SecurityDatasetFence incrementContentVersion();
    }
}
