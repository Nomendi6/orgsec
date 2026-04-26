package com.nomendi6.orgsec.storage.redis.invalidation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for InvalidationEvent.
 */
class InvalidationEventTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void constructor_validParameters_createsEvent() {
        // Given
        InvalidationType type = InvalidationType.PERSON_CHANGED;
        Long entityId = 123L;
        String instanceId = "instance-1";
        long timestamp = System.currentTimeMillis();

        // When
        InvalidationEvent event = new InvalidationEvent(type, entityId, instanceId, timestamp);

        // Then
        assertThat(event.getType()).isEqualTo(type);
        assertThat(event.getEntityId()).isEqualTo(entityId);
        assertThat(event.getInstanceId()).isEqualTo(instanceId);
        assertThat(event.getTimestamp()).isEqualTo(timestamp);
    }

    @Test
    void constructor_nullEntityId_allowed() {
        // Given - SECURITY_REFRESH doesn't need entityId
        InvalidationType type = InvalidationType.SECURITY_REFRESH;

        // When
        InvalidationEvent event = new InvalidationEvent(type, null, "instance-1", System.currentTimeMillis());

        // Then
        assertThat(event.getEntityId()).isNull();
        assertThat(event.getType()).isEqualTo(InvalidationType.SECURITY_REFRESH);
    }

    @Test
    void serialize_toJson_validFormat() throws Exception {
        // Given
        InvalidationEvent event = new InvalidationEvent(
            InvalidationType.PERSON_CHANGED,
            123L,
            "instance-1",
            1642345678901L
        );

        // When
        String json = objectMapper.writeValueAsString(event);

        // Then
        assertThat(json).contains("\"type\":\"PERSON_CHANGED\"");
        assertThat(json).contains("\"entityId\":123");
        assertThat(json).contains("\"instanceId\":\"instance-1\"");
        assertThat(json).contains("\"timestamp\":1642345678901");
    }

    @Test
    void deserialize_fromJson_validObject() throws Exception {
        // Given
        String json = "{\"type\":\"ORG_CHANGED\",\"entityId\":456,\"instanceId\":\"instance-2\",\"timestamp\":1642345678901}";

        // When
        InvalidationEvent event = objectMapper.readValue(json, InvalidationEvent.class);

        // Then
        assertThat(event).isNotNull();
        assertThat(event.getType()).isEqualTo(InvalidationType.ORG_CHANGED);
        assertThat(event.getEntityId()).isEqualTo(456L);
        assertThat(event.getInstanceId()).isEqualTo("instance-2");
        assertThat(event.getTimestamp()).isEqualTo(1642345678901L);
    }

    @Test
    void roundTrip_serializeAndDeserialize_preservesData() throws Exception {
        // Given
        InvalidationEvent original = new InvalidationEvent(
            InvalidationType.ROLE_CHANGED,
            789L,
            "instance-3",
            System.currentTimeMillis()
        );

        // When
        String json = objectMapper.writeValueAsString(original);
        InvalidationEvent deserialized = objectMapper.readValue(json, InvalidationEvent.class);

        // Then
        assertThat(deserialized.getType()).isEqualTo(original.getType());
        assertThat(deserialized.getEntityId()).isEqualTo(original.getEntityId());
        assertThat(deserialized.getInstanceId()).isEqualTo(original.getInstanceId());
        assertThat(deserialized.getTimestamp()).isEqualTo(original.getTimestamp());
    }

    @Test
    void equals_sameValues_returnsTrue() {
        // Given
        long timestamp = System.currentTimeMillis();
        InvalidationEvent event1 = new InvalidationEvent(
            InvalidationType.PERSON_CHANGED, 123L, "instance-1", timestamp
        );
        InvalidationEvent event2 = new InvalidationEvent(
            InvalidationType.PERSON_CHANGED, 123L, "instance-1", timestamp
        );

        // When/Then
        assertThat(event1).isEqualTo(event2);
        assertThat(event1.hashCode()).isEqualTo(event2.hashCode());
    }

    @Test
    void equals_differentType_returnsFalse() {
        // Given
        long timestamp = System.currentTimeMillis();
        InvalidationEvent event1 = new InvalidationEvent(
            InvalidationType.PERSON_CHANGED, 123L, "instance-1", timestamp
        );
        InvalidationEvent event2 = new InvalidationEvent(
            InvalidationType.ORG_CHANGED, 123L, "instance-1", timestamp
        );

        // When/Then
        assertThat(event1).isNotEqualTo(event2);
    }

    @Test
    void equals_differentEntityId_returnsFalse() {
        // Given
        long timestamp = System.currentTimeMillis();
        InvalidationEvent event1 = new InvalidationEvent(
            InvalidationType.PERSON_CHANGED, 123L, "instance-1", timestamp
        );
        InvalidationEvent event2 = new InvalidationEvent(
            InvalidationType.PERSON_CHANGED, 456L, "instance-1", timestamp
        );

        // When/Then
        assertThat(event1).isNotEqualTo(event2);
    }

    @Test
    void equals_differentInstanceId_returnsFalse() {
        // Given
        long timestamp = System.currentTimeMillis();
        InvalidationEvent event1 = new InvalidationEvent(
            InvalidationType.PERSON_CHANGED, 123L, "instance-1", timestamp
        );
        InvalidationEvent event2 = new InvalidationEvent(
            InvalidationType.PERSON_CHANGED, 123L, "instance-2", timestamp
        );

        // When/Then
        assertThat(event1).isNotEqualTo(event2);
    }

    @Test
    void equals_differentTimestamp_returnsFalse() {
        // Given
        InvalidationEvent event1 = new InvalidationEvent(
            InvalidationType.PERSON_CHANGED, 123L, "instance-1", 1000L
        );
        InvalidationEvent event2 = new InvalidationEvent(
            InvalidationType.PERSON_CHANGED, 123L, "instance-1", 2000L
        );

        // When/Then
        assertThat(event1).isNotEqualTo(event2);
    }

    @Test
    void toString_containsAllFields() {
        // Given
        InvalidationEvent event = new InvalidationEvent(
            InvalidationType.PERSON_CHANGED,
            123L,
            "instance-1",
            1642345678901L
        );

        // When
        String toString = event.toString();

        // Then
        assertThat(toString).contains("PERSON_CHANGED");
        assertThat(toString).contains("123");
        assertThat(toString).contains("instance-1");
        assertThat(toString).contains("1642345678901");
    }

    @Test
    void allInvalidationTypes_canBeSerialized() throws Exception {
        // Given - test all enum values
        for (InvalidationType type : InvalidationType.values()) {
            InvalidationEvent event = new InvalidationEvent(
                type, 1L, "instance", System.currentTimeMillis()
            );

            // When
            String json = objectMapper.writeValueAsString(event);
            InvalidationEvent deserialized = objectMapper.readValue(json, InvalidationEvent.class);

            // Then
            assertThat(deserialized.getType()).isEqualTo(type);
        }
    }
}
