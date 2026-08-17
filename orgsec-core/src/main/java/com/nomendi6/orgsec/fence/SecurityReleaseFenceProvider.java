package com.nomendi6.orgsec.fence;

/**
 * Loads and cryptographically verifies the external release fence.
 *
 * <p>The backing record and its trust material must be outside both the Redis persistence domain
 * and the source-database backup/restore domain. Implementations must fail closed when the record
 * is missing, malformed, untrusted or has an invalid signature.</p>
 */
@FunctionalInterface
public interface SecurityReleaseFenceProvider {

    /**
     * Loads a fresh external record and verifies it before returning.
     *
     * @return verified release fence; never {@code null}
     * @throws RuntimeException when loading or verification fails
     */
    SecurityReleaseFence loadVerifiedFence();
}
