package com.nomendi6.orgsec.storage.redis.protocol;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RedisSnapshotDigestAccumulatorTest {

    private static final byte[] ZERO_DIGEST = new byte[32];

    @Test
    void assignsSixExplicitStableFamilyCodesAndCanonicalPositions() {
        assertThat(RedisSnapshotFamilyCode.PERSONS.wireCode()).isEqualTo((byte) 0x01);
        assertThat(RedisSnapshotFamilyCode.ORGANIZATIONS.wireCode()).isEqualTo((byte) 0x02);
        assertThat(RedisSnapshotFamilyCode.PARTY_ROLES.wireCode()).isEqualTo((byte) 0x03);
        assertThat(RedisSnapshotFamilyCode.POSITION_ROLES.wireCode()).isEqualTo((byte) 0x04);
        assertThat(RedisSnapshotFamilyCode.ROLES.wireCode()).isEqualTo((byte) 0x05);
        assertThat(RedisSnapshotFamilyCode.PRIVILEGES.wireCode()).isEqualTo((byte) 0x06);
        assertThat(RedisSnapshotFamilyCode.familyCount()).isEqualTo(6);
        assertThat(RedisSnapshotFamilyCode.atCanonicalIndex(0))
            .isEqualTo(RedisSnapshotFamilyCode.PERSONS);
        assertThat(RedisSnapshotFamilyCode.atCanonicalIndex(5))
            .isEqualTo(RedisSnapshotFamilyCode.PRIVILEGES);
    }

    @Test
    void isolatesIdenticalEntryBytesByFamily() {
        RedisCanonicalEntry entry = entry("same-key", "same-payload");

        byte[] persons = RedisSnapshotFamilyAccumulator.entryDigest(
            RedisSnapshotFamilyCode.PERSONS,
            entry
        );
        byte[] organizations = RedisSnapshotFamilyAccumulator.entryDigest(
            RedisSnapshotFamilyCode.ORGANIZATIONS,
            entry
        );

        assertThat(persons).isNotEqualTo(organizations);
    }

    @Test
    void lengthPrefixesPreventKeyPayloadDelimiterCollisions() {
        byte[] first = RedisSnapshotFamilyAccumulator.entryDigest(
            RedisSnapshotFamilyCode.PRIVILEGES,
            entry("a", "bc")
        );
        byte[] second = RedisSnapshotFamilyAccumulator.entryDigest(
            RedisSnapshotFamilyCode.PRIVILEGES,
            entry("ab", "c")
        );
        byte[] third = RedisSnapshotFamilyAccumulator.entryDigest(
            RedisSnapshotFamilyCode.PRIVILEGES,
            entry("a:", "bc")
        );
        byte[] fourth = RedisSnapshotFamilyAccumulator.entryDigest(
            RedisSnapshotFamilyCode.PRIVILEGES,
            entry("a", ":bc")
        );

        assertThat(first).isNotEqualTo(second);
        assertThat(third).isNotEqualTo(fourth);
    }

    @Test
    void fixedPrivilegeEntryDigestHasStableGoldenBytes() {
        byte[] digest = RedisSnapshotFamilyAccumulator.entryDigest(
            RedisSnapshotFamilyCode.PRIVILEGES,
            entry("invoice:read", "{\"allowed\":true}")
        );

        assertThat(HexFormat.of().formatHex(digest))
            .isEqualTo("53e5353b6a1199d2ae6b2923f5dce1d7761bed36936424a617176618ca70b7b3");
    }

    @Test
    void fixedTwoEntryPrivilegeFamilyHasStableGoldenDigestAndAccounting() {
        RedisSnapshotFamilyDigest digest = fixedPrivilegesFamily();

        assertThat(digest.family()).isEqualTo(RedisSnapshotFamilyCode.PRIVILEGES);
        assertThat(digest.entryCount()).isEqualTo(2);
        assertThat(digest.logicalBytes()).isEqualTo(114);
        assertThat(HexFormat.of().formatHex(digest.digest()))
            .isEqualTo("647ca07368f720d24d3355d7f5f5824f9120deadac41197bad35ce1a4b50479c");
    }

    @Test
    void sixFamilyContentWithFixedPrivilegesHasStableGoldenBytes() {
        RedisSnapshotContentAccumulator accumulator = new RedisSnapshotContentAccumulator();
        accumulator.add(empty(RedisSnapshotFamilyCode.PERSONS));
        accumulator.add(empty(RedisSnapshotFamilyCode.ORGANIZATIONS));
        accumulator.add(empty(RedisSnapshotFamilyCode.PARTY_ROLES));
        accumulator.add(empty(RedisSnapshotFamilyCode.POSITION_ROLES));
        accumulator.add(empty(RedisSnapshotFamilyCode.ROLES));
        accumulator.add(fixedPrivilegesFamily());

        RedisSnapshotContentDigest digest = accumulator.finish();
        assertThat(digest.entryCount()).isEqualTo(2);
        assertThat(digest.logicalBytes()).isEqualTo(114);
        assertThat(digest.familyDigests())
            .extracting(RedisSnapshotFamilyDigest::family)
            .containsExactly(
                RedisSnapshotFamilyCode.PERSONS,
                RedisSnapshotFamilyCode.ORGANIZATIONS,
                RedisSnapshotFamilyCode.PARTY_ROLES,
                RedisSnapshotFamilyCode.POSITION_ROLES,
                RedisSnapshotFamilyCode.ROLES,
                RedisSnapshotFamilyCode.PRIVILEGES
            );
        assertThat(digest.familyDigest(RedisSnapshotFamilyCode.PRIVILEGES).entryCount())
            .isEqualTo(2);
        assertThat(digest.familyDigest(RedisSnapshotFamilyCode.PRIVILEGES).logicalBytes())
            .isEqualTo(114);
        assertThat(HexFormat.of().formatHex(digest.digest()))
            .isEqualTo("04c8e6b9a7ff955182203d58db205731bb9a84c787dd975a91008a3dfb376569");
    }

    @Test
    void explicitSorterMakesBatchInsertionOrderDigestInvariant() {
        RedisCanonicalEntry alpha = entry("alpha", "one");
        RedisCanonicalEntry beta = entry("beta", "two");
        RedisCanonicalEntry gamma = entry("gamma", "three");

        RedisSnapshotFamilyDigest first = accumulateSorted(
            RedisCanonicalEntryOrder.sortedCopy(List.of(gamma, alpha, beta))
        );
        RedisSnapshotFamilyDigest second = accumulateSorted(
            RedisCanonicalEntryOrder.sortedCopy(List.of(beta, gamma, alpha))
        );

        assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
        assertThat(first.entryCount()).isEqualTo(3);
    }

    @Test
    void canonicalContentDigestIgnoresPhysicalSnapshotKeyNames() {
        RedisSnapshotContentDigest first = contentWithPersonsAt(
            "orgsec:{tenant}:snapshot:00000000-0000-4000-8000-000000000001:persons:data",
            "orgsec:{tenant}:snapshot:00000000-0000-4000-8000-000000000001:persons:index"
        );
        RedisSnapshotContentDigest second = contentWithPersonsAt(
            "orgsec:{tenant}:snapshot:00000000-0000-4000-8000-000000000002:persons:data-longer",
            "orgsec:{tenant}:snapshot:00000000-0000-4000-8000-000000000002:persons:index-longer"
        );

        assertThat(first.digest()).isEqualTo(second.digest());
        assertThat(first.logicalBytes()).isNotEqualTo(second.logicalBytes());
        assertThat(first.familyDigest(RedisSnapshotFamilyCode.PERSONS).digest())
            .isEqualTo(second.familyDigest(RedisSnapshotFamilyCode.PERSONS).digest());
    }

    @Test
    void streamingFamilyAccumulatorRejectsDuplicateAndOutOfOrderKeys() {
        RedisSnapshotFamilyAccumulator duplicate = accumulator();
        duplicate.add(entry("a", "first"));
        assertThatThrownBy(() -> duplicate.add(entry("a", "second")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("strictly increasing");

        RedisSnapshotFamilyAccumulator outOfOrder = accumulator();
        outOfOrder.add(entry("b", "second"));
        assertThatThrownBy(() -> outOfOrder.add(entry("a", "first")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("strictly increasing");
    }

    @Test
    void contentAccumulatorRequiresAllSixFamiliesInExactCanonicalOrder() {
        RedisSnapshotContentAccumulator missing = new RedisSnapshotContentAccumulator();
        missing.add(empty(RedisSnapshotFamilyCode.PERSONS));
        assertThatThrownBy(missing::finish)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("ORGANIZATIONS");

        RedisSnapshotContentAccumulator permuted = new RedisSnapshotContentAccumulator();
        assertThatThrownBy(() -> permuted.add(empty(RedisSnapshotFamilyCode.ORGANIZATIONS)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("PERSONS");

        RedisSnapshotContentAccumulator complete = emptyContentAccumulator();
        RedisSnapshotContentDigest result = complete.finish();
        assertThat(result.entryCount()).isZero();
        assertThat(result.logicalBytes()).isZero();
        assertThatThrownBy(complete::finish)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("finished");
    }

    @Test
    void emptySixFamilyDigestHasStableGoldenBytes() {
        RedisSnapshotContentDigest digest = emptyContentAccumulator().finish();

        assertThat(HexFormat.of().formatHex(digest.digest()))
            .isEqualTo("515520d7f86727612f549af141cf2a2def4424560fdd4e979a0ecefb8c67fc1a");
    }

    @Test
    void accountsFamilyKeyNamesOnceAndOnlyWhenTheFamilyIsNonEmpty() {
        RedisSnapshotFamilyAccumulator empty = accumulator();
        assertThat(empty.finish().logicalBytes()).isZero();

        RedisSnapshotFamilyAccumulator populated = accumulator();
        populated.add(entry("a", "one"));
        populated.add(entry("bb", "two"));

        RedisSnapshotFamilyDigest result = populated.finish();
        assertThat(result.logicalBytes()).isEqualTo(21);
    }

    @Test
    void detectsOverflowWhenAggregatingFamilyAccounting() {
        RedisSnapshotContentAccumulator accumulator = new RedisSnapshotContentAccumulator();
        accumulator.add(new RedisSnapshotFamilyDigest(
            RedisSnapshotFamilyCode.PERSONS,
            1,
            Long.MAX_VALUE,
            ZERO_DIGEST
        ));

        assertThatThrownBy(() -> accumulator.add(new RedisSnapshotFamilyDigest(
            RedisSnapshotFamilyCode.ORGANIZATIONS,
            1,
            1,
            ZERO_DIGEST
        )))
            .isInstanceOf(ArithmeticException.class);
        accumulator.add(empty(RedisSnapshotFamilyCode.ORGANIZATIONS));
        accumulator.add(empty(RedisSnapshotFamilyCode.PARTY_ROLES));
        accumulator.add(empty(RedisSnapshotFamilyCode.POSITION_ROLES));
        accumulator.add(empty(RedisSnapshotFamilyCode.ROLES));
        accumulator.add(empty(RedisSnapshotFamilyCode.PRIVILEGES));
        RedisSnapshotContentDigest result = accumulator.finish();
        assertThat(result.logicalBytes()).isEqualTo(Long.MAX_VALUE);
        assertThat(result.entryCount()).isEqualTo(1);
        assertThatThrownBy(() -> RedisSnapshotLogicalBytes.addExact(Long.MAX_VALUE, 1))
            .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void rejectsNullInputsAndUseAfterFinish() {
        RedisSnapshotFamilyAccumulator family = accumulator();
        assertThatThrownBy(() -> family.add(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("entry");
        assertThatThrownBy(() -> RedisSnapshotFamilyAccumulator.entryDigest(null, entry("a", "b")))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("family");
        assertThatThrownBy(() -> RedisSnapshotFamilyAccumulator.entryDigest(
            RedisSnapshotFamilyCode.PERSONS,
            null
        ))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("entry");
        family.finish();
        assertThatThrownBy(() -> family.add(entry("a", "b")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("finished");
        assertThatThrownBy(family::finish)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("finished");

        RedisSnapshotContentAccumulator content = new RedisSnapshotContentAccumulator();
        assertThatThrownBy(() -> content.add(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("familyDigest");

        RedisSnapshotContentAccumulator allFamilies = emptyContentAccumulator();
        assertThatThrownBy(() -> allFamilies.add(empty(RedisSnapshotFamilyCode.PRIVILEGES)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("all six");
    }

    @Test
    void digestResultsOwnDefensiveCopies() {
        byte[] familyBytes = ZERO_DIGEST.clone();
        RedisSnapshotFamilyDigest family = new RedisSnapshotFamilyDigest(
            RedisSnapshotFamilyCode.PERSONS,
            1,
            2,
            familyBytes
        );
        familyBytes[0] = 1;
        byte[] returnedFamilyBytes = family.digest();
        returnedFamilyBytes[1] = 2;

        RedisSnapshotContentDigest content = emptyContentAccumulator().finish();
        byte[] returnedContentBytes = content.digest();
        returnedContentBytes[1] = 2;

        assertThat(family.digest()).containsOnly(0);
        assertThat(content.digest()[1]).isNotEqualTo((byte) 2);
        assertThatThrownBy(() -> content.familyDigests().clear())
            .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> content.familyDigest(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("family");
    }

    @Test
    void rejectsInvalidDigestResultShapes() {
        assertThatThrownBy(() -> new RedisSnapshotFamilyDigest(
            null, 0, 0, ZERO_DIGEST
        )).isInstanceOf(NullPointerException.class).hasMessageContaining("family");
        assertThatThrownBy(() -> new RedisSnapshotFamilyDigest(
            RedisSnapshotFamilyCode.PERSONS, -1, 0, ZERO_DIGEST
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("entryCount");
        assertThatThrownBy(() -> new RedisSnapshotFamilyDigest(
            RedisSnapshotFamilyCode.PERSONS, 0, -1, ZERO_DIGEST
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("logicalBytes");
        assertThatThrownBy(() -> new RedisSnapshotFamilyDigest(
            RedisSnapshotFamilyCode.PERSONS, 0, 1, ZERO_DIGEST
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("both be zero");
        assertThatThrownBy(() -> new RedisSnapshotFamilyDigest(
            RedisSnapshotFamilyCode.PERSONS, 1, 0, ZERO_DIGEST
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("both be zero");
        assertThatThrownBy(() -> new RedisSnapshotFamilyDigest(
            RedisSnapshotFamilyCode.PERSONS, 0, 0, new byte[31]
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("32 bytes");
        assertThatThrownBy(() -> new RedisSnapshotContentDigest(
            null,
            empty(RedisSnapshotFamilyCode.ORGANIZATIONS),
            empty(RedisSnapshotFamilyCode.PARTY_ROLES),
            empty(RedisSnapshotFamilyCode.POSITION_ROLES),
            empty(RedisSnapshotFamilyCode.ROLES),
            empty(RedisSnapshotFamilyCode.PRIVILEGES)
        ))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("PERSONS");
        assertThatThrownBy(() -> new RedisSnapshotContentDigest(
            empty(RedisSnapshotFamilyCode.ORGANIZATIONS),
            empty(RedisSnapshotFamilyCode.ORGANIZATIONS),
            empty(RedisSnapshotFamilyCode.PARTY_ROLES),
            empty(RedisSnapshotFamilyCode.POSITION_ROLES),
            empty(RedisSnapshotFamilyCode.ROLES),
            empty(RedisSnapshotFamilyCode.PRIVILEGES)
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("PERSONS");
    }

    private static RedisSnapshotFamilyAccumulator accumulator() {
        return new RedisSnapshotFamilyAccumulator(
            RedisSnapshotFamilyCode.PRIVILEGES,
            "data",
            "index"
        );
    }

    private static RedisSnapshotFamilyDigest accumulateSorted(
        List<RedisCanonicalEntry> entries
    ) {
        RedisSnapshotFamilyAccumulator accumulator = accumulator();
        entries.forEach(accumulator::add);
        return accumulator.finish();
    }

    private static RedisSnapshotFamilyDigest empty(RedisSnapshotFamilyCode family) {
        return new RedisSnapshotFamilyAccumulator(family, "data", "index").finish();
    }

    private static RedisSnapshotFamilyDigest fixedPrivilegesFamily() {
        RedisSnapshotFamilyAccumulator accumulator = new RedisSnapshotFamilyAccumulator(
            RedisSnapshotFamilyCode.PRIVILEGES,
            "data:privileges",
            "index:privileges"
        );
        accumulator.add(entry("invoice:read", "{\"allowed\":true}"));
        accumulator.add(entry("invoice:write", "{\"allowed\":false}"));
        return accumulator.finish();
    }

    private static RedisSnapshotContentDigest contentWithPersonsAt(
        String dataKey,
        String indexKey
    ) {
        RedisSnapshotFamilyAccumulator persons = new RedisSnapshotFamilyAccumulator(
            RedisSnapshotFamilyCode.PERSONS,
            dataKey,
            indexKey
        );
        persons.add(entry("person-1", "{\"name\":\"Ada\"}"));

        RedisSnapshotContentAccumulator content = new RedisSnapshotContentAccumulator();
        content.add(persons.finish());
        content.add(empty(RedisSnapshotFamilyCode.ORGANIZATIONS));
        content.add(empty(RedisSnapshotFamilyCode.PARTY_ROLES));
        content.add(empty(RedisSnapshotFamilyCode.POSITION_ROLES));
        content.add(empty(RedisSnapshotFamilyCode.ROLES));
        content.add(empty(RedisSnapshotFamilyCode.PRIVILEGES));
        return content.finish();
    }

    private static RedisSnapshotContentAccumulator emptyContentAccumulator() {
        RedisSnapshotContentAccumulator accumulator = new RedisSnapshotContentAccumulator();
        accumulator.add(empty(RedisSnapshotFamilyCode.PERSONS));
        accumulator.add(empty(RedisSnapshotFamilyCode.ORGANIZATIONS));
        accumulator.add(empty(RedisSnapshotFamilyCode.PARTY_ROLES));
        accumulator.add(empty(RedisSnapshotFamilyCode.POSITION_ROLES));
        accumulator.add(empty(RedisSnapshotFamilyCode.ROLES));
        accumulator.add(empty(RedisSnapshotFamilyCode.PRIVILEGES));
        return accumulator;
    }

    private static RedisCanonicalEntry entry(String key, String payload) {
        return new RedisCanonicalEntry(bytes(key), bytes(payload));
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
