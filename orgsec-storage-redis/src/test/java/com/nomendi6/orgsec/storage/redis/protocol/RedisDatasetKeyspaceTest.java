package com.nomendi6.orgsec.storage.redis.protocol;

import com.nomendi6.orgsec.storage.redis.bootstrap.RedisSnapshotFamily;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RedisDatasetKeyspaceTest {

    private static final UUID SNAPSHOT_ID =
        UUID.fromString("11111111-1111-4111-8111-111111111111");

    @Test
    void buildsCanonicalDatasetIsolatedKeysInOneHashSlot() {
        RedisDatasetKeyspace keyspace = new RedisDatasetKeyspace("tenant-a");
        String hash = "80a707af7dc77ee1228f9127180f3964835e5beb4c4ab0d812f0fe7593579b3a";
        String tag = "{" + hash + "}";

        assertThat(keyspace.datasetHash()).isEqualTo(hash);
        assertThat(keyspace.hashTag()).isEqualTo(tag);
        assertThat(keyspace.controlKey()).isEqualTo("orgsec:v1:" + tag + ":control");
        assertThat(keyspace.leaseKey()).isEqualTo("orgsec:v1:" + tag + ":lease");
        assertThat(keyspace.leaseCounterKey())
            .isEqualTo("orgsec:v1:" + tag + ":lease-counter");
        assertThat(keyspace.manifestKey(SNAPSHOT_ID))
            .isEqualTo("orgsec:v1:" + tag + ":snapshot:" + SNAPSHOT_ID + ":manifest");
        assertThat(keyspace.familyKey(SNAPSHOT_ID, RedisSnapshotFamily.PERSONS))
            .isEqualTo(
                "orgsec:v1:" + tag + ":snapshot:" + SNAPSHOT_ID + ":family:persons"
            );
        assertThat(keyspace.familyIndexKey(SNAPSHOT_ID, RedisSnapshotFamily.PERSONS))
            .isEqualTo(
                "orgsec:v1:" + tag + ":snapshot:" + SNAPSHOT_ID + ":family:persons:index"
            );
    }

    @Test
    void hashesTheExactUtf8DatasetIdentifierWithoutPlatformEncodingOrTrimming() {
        RedisDatasetKeyspace unicode = new RedisDatasetKeyspace("žaba/租户");
        RedisDatasetKeyspace withSpaces = new RedisDatasetKeyspace(" tenant-a ");
        RedisDatasetKeyspace withoutSpaces = new RedisDatasetKeyspace("tenant-a");

        assertThat(unicode.datasetHash())
            .isEqualTo("a2e4335f79722ddd012373c5f7b682e585e94349733834be6cf9b6d4dc02d05e");
        assertThat(withSpaces.datasetHash()).isNotEqualTo(withoutSpaces.datasetHash());
        assertThat(withSpaces.controlKey()).isNotEqualTo(withoutSpaces.controlKey());
    }

    @Test
    void usesExplicitStableWireNamesForEverySnapshotFamily() {
        RedisDatasetKeyspace keyspace = new RedisDatasetKeyspace("tenant-a");

        assertThat(keyspace.familyKey(SNAPSHOT_ID, RedisSnapshotFamily.PERSONS))
            .endsWith(":family:persons");
        assertThat(keyspace.familyKey(SNAPSHOT_ID, RedisSnapshotFamily.ORGANIZATIONS))
            .endsWith(":family:organizations");
        assertThat(keyspace.familyKey(SNAPSHOT_ID, RedisSnapshotFamily.PARTY_ROLES))
            .endsWith(":family:party-roles");
        assertThat(keyspace.familyKey(SNAPSHOT_ID, RedisSnapshotFamily.POSITION_ROLES))
            .endsWith(":family:position-roles");
        assertThat(keyspace.familyKey(SNAPSHOT_ID, RedisSnapshotFamily.ROLES))
            .endsWith(":family:roles");
        assertThat(keyspace.familyKey(SNAPSHOT_ID, RedisSnapshotFamily.PRIVILEGES))
            .endsWith(":family:privileges");
    }

    @Test
    void rejectsMissingDatasetSnapshotAndFamilyIdentifiers() {
        assertThatThrownBy(() -> new RedisDatasetKeyspace(null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("securityDatasetId");
        assertThatThrownBy(() -> new RedisDatasetKeyspace(" \t"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("securityDatasetId");

        RedisDatasetKeyspace keyspace = new RedisDatasetKeyspace("tenant-a");
        assertThatThrownBy(() -> keyspace.manifestKey(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("snapshotId");
        assertThatThrownBy(() -> keyspace.familyKey(null, RedisSnapshotFamily.PERSONS))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("snapshotId");
        assertThatThrownBy(() -> keyspace.familyKey(SNAPSHOT_ID, null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("family");
        assertThatThrownBy(() -> keyspace.familyIndexKey(null, RedisSnapshotFamily.PERSONS))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("snapshotId");
        assertThatThrownBy(() -> keyspace.familyIndexKey(SNAPSHOT_ID, null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("family");
    }

    @Test
    void rejectsMalformedUtf16InsteadOfCollapsingDistinctDatasetIds() {
        String firstUnpairedSurrogate = "tenant-\uD800";
        String secondUnpairedSurrogate = "tenant-\uD801";

        assertThatThrownBy(() -> new RedisDatasetKeyspace(firstUnpairedSurrogate))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("well-formed Unicode");
        assertThatThrownBy(() -> new RedisDatasetKeyspace(secondUnpairedSurrogate))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("well-formed Unicode");
    }
}
