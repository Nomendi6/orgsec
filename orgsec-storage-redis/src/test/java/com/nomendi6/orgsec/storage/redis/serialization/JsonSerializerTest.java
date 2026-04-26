package com.nomendi6.orgsec.storage.redis.serialization;

import com.nomendi6.orgsec.model.PersonDef;
import com.nomendi6.orgsec.storage.redis.resilience.CacheSerializationException;
import com.nomendi6.orgsec.storage.redis.testutil.TestDataBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for JsonSerializer.
 */
class JsonSerializerTest {

    private JsonSerializer<PersonDef> serializer;

    @BeforeEach
    void setUp() {
        serializer = new JsonSerializer<>(PersonDef.class);
    }

    @Test
    void serialize_validPersonDef_returnsValidJson() {
        // Given
        PersonDef person = TestDataBuilder.buildPerson(123L, "John Doe");

        // When
        String json = serializer.serialize(person);

        // Then
        assertThat(json).isNotNull();
        assertThat(json).contains("\"personId\":123");
        assertThat(json).contains("\"personName\":\"John Doe\"");
        assertThat(json).contains("\"relatedUserId\":\"user-123\"");
    }

    @Test
    void serialize_nullValue_returnsNull() {
        // When
        String json = serializer.serialize(null);

        // Then
        assertThat(json).isNull();
    }

    @Test
    void serialize_personWithNullFields_excludesNullFields() {
        // Given
        PersonDef person = new PersonDef(1L, "Test");
        // relatedUserId, relatedUserLogin, etc. are null

        // When
        String json = serializer.serialize(person);

        // Then
        assertThat(json).isNotNull();
        assertThat(json).contains("\"personId\":1");
        assertThat(json).contains("\"personName\":\"Test\"");
        // Null fields should be excluded
        assertThat(json).doesNotContain("\"relatedUserId\"");
        assertThat(json).doesNotContain("\"defaultCompanyId\"");
    }

    @Test
    void deserialize_validJson_returnsPersonDef() {
        // Given
        String json = "{\"personId\":123,\"personName\":\"John Doe\",\"relatedUserId\":\"user-123\"}";

        // When
        PersonDef person = serializer.deserialize(json);

        // Then
        assertThat(person).isNotNull();
        assertThat(person.personId).isEqualTo(123L);
        assertThat(person.personName).isEqualTo("John Doe");
        assertThat(person.relatedUserId).isEqualTo("user-123");
    }

    @Test
    void deserialize_nullJson_returnsNull() {
        // When
        PersonDef person = serializer.deserialize(null);

        // Then
        assertThat(person).isNull();
    }

    @Test
    void deserialize_emptyJson_returnsNull() {
        // When
        PersonDef person = serializer.deserialize("");

        // Then
        assertThat(person).isNull();
    }

    @Test
    void deserialize_whitespaceOnlyJson_returnsNull() {
        // When
        PersonDef person = serializer.deserialize("   ");

        // Then
        assertThat(person).isNull();
    }

    @Test
    void deserialize_invalidJson_throwsException() {
        // Given
        String invalidJson = "{invalid json}";

        // When/Then
        assertThatThrownBy(() -> serializer.deserialize(invalidJson))
            .isInstanceOf(CacheSerializationException.class)
            .hasMessageContaining("Failed to deserialize");
    }

    @Test
    void deserialize_jsonWithUnknownFields_ignoresUnknownFields() {
        // Given - JSON with extra field that doesn't exist in PersonDef
        String json = "{\"personId\":123,\"personName\":\"John\",\"unknownField\":\"value\"}";

        // When
        PersonDef person = serializer.deserialize(json);

        // Then - should still deserialize successfully (forward compatibility)
        assertThat(person).isNotNull();
        assertThat(person.personId).isEqualTo(123L);
        assertThat(person.personName).isEqualTo("John");
    }

    @Test
    void roundTrip_serializeAndDeserialize_preservesData() {
        // Given
        PersonDef original = TestDataBuilder.buildPerson(456L, "Jane Doe");
        original.setDefaultCompanyId(100L);
        original.setDefaultOrgunitId(200L);

        // When
        String json = serializer.serialize(original);
        PersonDef deserialized = serializer.deserialize(json);

        // Then
        assertThat(deserialized).isNotNull();
        assertThat(deserialized.personId).isEqualTo(original.personId);
        assertThat(deserialized.personName).isEqualTo(original.personName);
        assertThat(deserialized.relatedUserId).isEqualTo(original.relatedUserId);
        assertThat(deserialized.relatedUserLogin).isEqualTo(original.relatedUserLogin);
        assertThat(deserialized.defaultCompanyId).isEqualTo(original.defaultCompanyId);
        assertThat(deserialized.defaultOrgunitId).isEqualTo(original.defaultOrgunitId);
    }

    @Test
    void roundTrip_personWithOrganizations_preservesNestedObjects() {
        // Given
        PersonDef original = TestDataBuilder.buildPersonWithOrganizations(789L);

        // When
        String json = serializer.serialize(original);
        PersonDef deserialized = serializer.deserialize(json);

        // Then
        assertThat(deserialized).isNotNull();
        assertThat(deserialized.personId).isEqualTo(789L);
        assertThat(deserialized.organizationsMap).isNotNull();
        assertThat(deserialized.organizationsMap).hasSize(2);
        assertThat(deserialized.organizationsMap.get(1L)).isNotNull();
        assertThat(deserialized.organizationsMap.get(1L).organizationName).isEqualTo("Organization 1");
    }

    @Test
    void serialize_producesCompactJson() {
        // Given
        PersonDef person = TestDataBuilder.buildPerson();

        // When
        String json = serializer.serialize(person);

        // Then - compact format (no pretty printing)
        assertThat(json).doesNotContain("\n");
        assertThat(json).doesNotContain("  "); // No double spaces for indentation
    }

    @Test
    void getTargetType_returnsCorrectClass() {
        // When
        Class<PersonDef> targetType = serializer.getTargetType();

        // Then
        assertThat(targetType).isEqualTo(PersonDef.class);
    }

    @Test
    void getObjectMapper_returnsConfiguredMapper() {
        // When
        var mapper = serializer.getObjectMapper();

        // Then
        assertThat(mapper).isNotNull();
    }
}
