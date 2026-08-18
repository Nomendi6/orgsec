package com.nomendi6.orgsec.storage.redis.protocol;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Explicit canonicalization boundary for one bounded collection received in arbitrary order.
 *
 * <p>Sorting separate batches does not establish whole-family order. Production sealing must read
 * the complete family through its Redis sorted index; the streaming accumulator then rejects any
 * duplicate or globally out-of-order key.</p>
 */
final class RedisCanonicalEntryOrder {

    private static final Comparator<RedisCanonicalEntry> UNSIGNED_KEY_ORDER =
        RedisCanonicalEntry::compareCanonicalKeyTo;

    private RedisCanonicalEntryOrder() {
    }

    /**
     * Returns an immutable copy ordered by unsigned lexicographic canonical-key bytes.
     *
     * @param entries one bounded collection of unordered entries
     * @return strictly ordered immutable entries
     */
    static List<RedisCanonicalEntry> sortedCopy(Collection<RedisCanonicalEntry> entries) {
        Objects.requireNonNull(entries, "entries must not be null");
        List<RedisCanonicalEntry> sorted = new ArrayList<>(entries.size());
        for (RedisCanonicalEntry entry : entries) {
            sorted.add(Objects.requireNonNull(entry, "entries must not contain null"));
        }
        sorted.sort(UNSIGNED_KEY_ORDER);
        for (int index = 1; index < sorted.size(); index++) {
            if (UNSIGNED_KEY_ORDER.compare(sorted.get(index - 1), sorted.get(index)) == 0) {
                throw new IllegalArgumentException("canonical entry keys must be unique");
            }
        }
        return List.copyOf(sorted);
    }
}
