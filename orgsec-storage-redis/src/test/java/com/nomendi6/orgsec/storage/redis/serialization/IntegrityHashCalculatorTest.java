package com.nomendi6.orgsec.storage.redis.serialization;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for IntegrityHashCalculator.
 */
class IntegrityHashCalculatorTest {

    private IntegrityHashCalculator calculator;

    @BeforeEach
    void setUp() {
        calculator = new IntegrityHashCalculator();
    }

    @Test
    void calculateHash_validJson_returnsSHA256Hash() {
        // Given
        String json = "{\"name\":\"test\",\"id\":123}";

        // When
        String hash = calculator.calculateHash(json);

        // Then
        assertThat(hash).isNotNull();
        assertThat(hash).hasSize(64); // SHA-256 produces 64 hex characters
        assertThat(hash).matches("[a-f0-9]{64}"); // Hex string pattern
    }

    @Test
    void calculateHash_sameJson_returnsSameHash() {
        // Given
        String json = "{\"name\":\"test\",\"id\":123}";

        // When
        String hash1 = calculator.calculateHash(json);
        String hash2 = calculator.calculateHash(json);

        // Then
        assertThat(hash1).isEqualTo(hash2);
    }

    @Test
    void calculateHash_differentJson_returnsDifferentHash() {
        // Given
        String json1 = "{\"name\":\"test1\",\"id\":123}";
        String json2 = "{\"name\":\"test2\",\"id\":123}";

        // When
        String hash1 = calculator.calculateHash(json1);
        String hash2 = calculator.calculateHash(json2);

        // Then
        assertThat(hash1).isNotEqualTo(hash2);
    }

    @Test
    void calculateHash_unsortedJson_producesConsistentHash() {
        // Given - same data, different key order
        String json1 = "{\"name\":\"test\",\"id\":123}";
        String json2 = "{\"id\":123,\"name\":\"test\"}";

        // When
        String hash1 = calculator.calculateHash(json1);
        String hash2 = calculator.calculateHash(json2);

        // Then - should be same after canonicalization (sorted keys)
        assertThat(hash1).isEqualTo(hash2);
    }

    @Test
    void calculateHash_jsonWithMetadata_ignoresMetadata() {
        // Given - one with _metadata, one without
        String jsonWithMetadata = "{\"name\":\"test\",\"id\":123,\"_metadata\":{\"version\":\"1.0\"}}";
        String jsonWithoutMetadata = "{\"name\":\"test\",\"id\":123}";

        // When
        String hash1 = calculator.calculateHash(jsonWithMetadata);
        String hash2 = calculator.calculateHash(jsonWithoutMetadata);

        // Then - should be same (metadata ignored)
        assertThat(hash1).isEqualTo(hash2);
    }

    @Test
    void calculateHash_nullJson_throwsException() {
        // When/Then
        assertThatThrownBy(() -> calculator.calculateHash(null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("cannot be null");
    }

    @Test
    void calculateHash_emptyJson_throwsException() {
        // When/Then
        assertThatThrownBy(() -> calculator.calculateHash(""))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("cannot be null or empty");
    }

    @Test
    void calculateHash_whitespaceOnlyJson_throwsException() {
        // When/Then
        assertThatThrownBy(() -> calculator.calculateHash("   "))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("cannot be null or empty");
    }

    @Test
    void verifyHash_validHash_returnsTrue() {
        // Given
        String json = "{\"name\":\"test\",\"id\":123}";
        String expectedHash = calculator.calculateHash(json);

        // When
        boolean result = calculator.verifyHash(json, expectedHash);

        // Then
        assertThat(result).isTrue();
    }

    @Test
    void verifyHash_invalidHash_returnsFalse() {
        // Given
        String json = "{\"name\":\"test\",\"id\":123}";
        String wrongHash = "0000000000000000000000000000000000000000000000000000000000000000";

        // When
        boolean result = calculator.verifyHash(json, wrongHash);

        // Then
        assertThat(result).isFalse();
    }

    @Test
    void verifyHash_nullJson_returnsFalse() {
        // Given
        String hash = "somehash";

        // When
        boolean result = calculator.verifyHash(null, hash);

        // Then
        assertThat(result).isFalse();
    }

    @Test
    void verifyHash_nullHash_returnsFalse() {
        // Given
        String json = "{\"name\":\"test\"}";

        // When
        boolean result = calculator.verifyHash(json, null);

        // Then
        assertThat(result).isFalse();
    }

    @Test
    void verifyHash_caseInsensitive_returnsTrue() {
        // Given
        String json = "{\"name\":\"test\",\"id\":123}";
        String hash = calculator.calculateHash(json);
        String upperCaseHash = hash.toUpperCase();

        // When
        boolean result = calculator.verifyHash(json, upperCaseHash);

        // Then
        assertThat(result).isTrue();
    }

    @Test
    void calculateHash_complexNestedJson_handlesCorrectly() {
        // Given
        String json = "{\"person\":{\"name\":\"John\",\"orgs\":[{\"id\":1,\"name\":\"Org1\"},{\"id\":2,\"name\":\"Org2\"}]}}";

        // When
        String hash = calculator.calculateHash(json);

        // Then
        assertThat(hash).isNotNull();
        assertThat(hash).hasSize(64);
    }

    @Test
    void calculateHash_invalidJson_throwsException() {
        // Given - malformed JSON
        String invalidJson = "{invalid json";

        // When/Then
        assertThatThrownBy(() -> calculator.calculateHash(invalidJson))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Failed to calculate hash");
    }
}
