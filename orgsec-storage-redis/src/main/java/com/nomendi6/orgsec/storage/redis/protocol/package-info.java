/**
 * Strict, fail-closed Redis snapshot protocol internals.
 *
 * <p>Wire bytes are decoded only at the atomic primary-snapshot boundary. That boundary fetches
 * only the ordered canonical hash fields, applies per-field byte bounds before retrieving their
 * values and uses a UTF-8 decoder configured to report malformed or unmappable input. The
 * control-envelope codec independently owns the exact field set, the same byte bounds and the
 * canonical value grammar.</p>
 */
package com.nomendi6.orgsec.storage.redis.protocol;
