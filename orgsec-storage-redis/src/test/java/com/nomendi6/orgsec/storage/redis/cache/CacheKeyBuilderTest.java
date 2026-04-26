package com.nomendi6.orgsec.storage.redis.cache;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for CacheKeyBuilder.
 */
class CacheKeyBuilderTest {

    @Test
    void buildPersonKey_withoutObfuscation_returnsPlainKey() {
        // Given
        CacheKeyBuilder builder = new CacheKeyBuilder(false);

        // When
        String key = builder.buildPersonKey(123L);

        // Then
        assertThat(key).isEqualTo("orgsec:p:123");
    }

    @Test
    void buildPersonKey_withObfuscation_returnsHashedKey() {
        // Given
        CacheKeyBuilder builder = new CacheKeyBuilder(true);

        // When
        String key = builder.buildPersonKey(123L);

        // Then
        assertThat(key).startsWith("orgsec:");
        assertThat(key).hasSize(71); // "orgsec:" (7) + 64 hex chars
        assertThat(key).isNotEqualTo("orgsec:p:123"); // Not plain key
    }

    @Test
    void buildPersonKey_nullUserId_throwsException() {
        // Given
        CacheKeyBuilder builder = new CacheKeyBuilder(false);

        // When/Then
        assertThatThrownBy(() -> builder.buildPersonKey(null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("User ID cannot be null");
    }

    @Test
    void buildOrganizationKey_withoutObfuscation_returnsPlainKey() {
        // Given
        CacheKeyBuilder builder = new CacheKeyBuilder(false);

        // When
        String key = builder.buildOrganizationKey(456L);

        // Then
        assertThat(key).isEqualTo("orgsec:o:456");
    }

    @Test
    void buildOrganizationKey_nullOrgId_throwsException() {
        // Given
        CacheKeyBuilder builder = new CacheKeyBuilder(false);

        // When/Then
        assertThatThrownBy(() -> builder.buildOrganizationKey(null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Organization ID cannot be null");
    }

    @Test
    void buildRoleKey_withoutObfuscation_returnsPlainKey() {
        // Given
        CacheKeyBuilder builder = new CacheKeyBuilder(false);

        // When
        String key = builder.buildRoleKey(789L);

        // Then
        assertThat(key).isEqualTo("orgsec:r:789");
    }

    @Test
    void buildRoleKey_nullRoleId_throwsException() {
        // Given
        CacheKeyBuilder builder = new CacheKeyBuilder(false);

        // When/Then
        assertThatThrownBy(() -> builder.buildRoleKey(null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Role ID cannot be null");
    }

    @Test
    void buildPrivilegeKey_withoutObfuscation_returnsPlainKey() {
        // Given
        CacheKeyBuilder builder = new CacheKeyBuilder(false);

        // When
        String key = builder.buildPrivilegeKey(999L);

        // Then
        assertThat(key).isEqualTo("orgsec:priv:999");
    }

    @Test
    void buildPrivilegeKey_nullPrivilegeId_throwsException() {
        // Given
        CacheKeyBuilder builder = new CacheKeyBuilder(false);

        // When/Then
        assertThatThrownBy(() -> builder.buildPrivilegeKey(null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Privilege ID cannot be null");
    }

    @Test
    void personKeysPattern_returnsCorrectPattern() {
        // Given
        CacheKeyBuilder builder = new CacheKeyBuilder(false);

        // When
        String pattern = builder.personKeysPattern();

        // Then
        assertThat(pattern).isEqualTo("orgsec:p:*");
    }

    @Test
    void organizationKeysPattern_returnsCorrectPattern() {
        // Given
        CacheKeyBuilder builder = new CacheKeyBuilder(false);

        // When
        String pattern = builder.organizationKeysPattern();

        // Then
        assertThat(pattern).isEqualTo("orgsec:o:*");
    }

    @Test
    void roleKeysPattern_returnsCorrectPattern() {
        // Given
        CacheKeyBuilder builder = new CacheKeyBuilder(false);

        // When
        String pattern = builder.roleKeysPattern();

        // Then
        assertThat(pattern).isEqualTo("orgsec:r:*");
    }

    @Test
    void allKeysPattern_returnsCorrectPattern() {
        // Given
        CacheKeyBuilder builder = new CacheKeyBuilder(false);

        // When
        String pattern = builder.allKeysPattern();

        // Then
        assertThat(pattern).isEqualTo("orgsec:*");
    }

    @Test
    void buildPersonKey_sameIdDifferentInstances_returnsSameKey() {
        // Given
        CacheKeyBuilder builder1 = new CacheKeyBuilder(false);
        CacheKeyBuilder builder2 = new CacheKeyBuilder(false);

        // When
        String key1 = builder1.buildPersonKey(123L);
        String key2 = builder2.buildPersonKey(123L);

        // Then
        assertThat(key1).isEqualTo(key2);
    }

    @Test
    void buildPersonKey_withObfuscation_sameIdProducesSameHash() {
        // Given
        CacheKeyBuilder builder1 = new CacheKeyBuilder(true);
        CacheKeyBuilder builder2 = new CacheKeyBuilder(true);

        // When
        String key1 = builder1.buildPersonKey(123L);
        String key2 = builder2.buildPersonKey(123L);

        // Then
        assertThat(key1).isEqualTo(key2); // Deterministic hash
    }

    @Test
    void buildPersonKey_withObfuscation_differentIdsProduceDifferentHashes() {
        // Given
        CacheKeyBuilder builder = new CacheKeyBuilder(true);

        // When
        String key1 = builder.buildPersonKey(123L);
        String key2 = builder.buildPersonKey(456L);

        // Then
        assertThat(key1).isNotEqualTo(key2);
    }

    @Test
    void isObfuscateKeys_returnsCorrectValue() {
        // Given
        CacheKeyBuilder builderPlain = new CacheKeyBuilder(false);
        CacheKeyBuilder builderObfuscated = new CacheKeyBuilder(true);

        // When/Then
        assertThat(builderPlain.isObfuscateKeys()).isFalse();
        assertThat(builderObfuscated.isObfuscateKeys()).isTrue();
    }
}
