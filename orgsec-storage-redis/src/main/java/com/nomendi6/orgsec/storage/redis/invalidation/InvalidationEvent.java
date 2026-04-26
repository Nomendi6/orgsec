package com.nomendi6.orgsec.storage.redis.invalidation;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/**
 * Event model for cache invalidation.
 * <p>
 * This event is published via Redis Pub/Sub when cached data changes,
 * allowing all application instances to invalidate their local caches.
 * </p>
 */
public class InvalidationEvent {

    private final InvalidationType type;
    private final Long entityId;
    private final String instanceId;
    private final long timestamp;

    /**
     * Constructs a new invalidation event.
     *
     * @param type       the invalidation type
     * @param entityId   the entity ID (can be null for SECURITY_REFRESH)
     * @param instanceId the UUID of the instance that published this event
     * @param timestamp  the event timestamp (epoch millis)
     */
    @JsonCreator
    public InvalidationEvent(
        @JsonProperty("type") InvalidationType type,
        @JsonProperty("entityId") Long entityId,
        @JsonProperty("instanceId") String instanceId,
        @JsonProperty("timestamp") long timestamp
    ) {
        this.type = type;
        this.entityId = entityId;
        this.instanceId = instanceId;
        this.timestamp = timestamp;
    }

    /**
     * Returns the invalidation type.
     *
     * @return the type
     */
    public InvalidationType getType() {
        return type;
    }

    /**
     * Returns the entity ID.
     *
     * @return the entity ID, or null for SECURITY_REFRESH
     */
    public Long getEntityId() {
        return entityId;
    }

    /**
     * Returns the instance ID that published this event.
     *
     * @return the instance ID
     */
    public String getInstanceId() {
        return instanceId;
    }

    /**
     * Returns the event timestamp.
     *
     * @return the timestamp (epoch millis)
     */
    public long getTimestamp() {
        return timestamp;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof InvalidationEvent)) return false;
        InvalidationEvent that = (InvalidationEvent) o;
        return timestamp == that.timestamp &&
            type == that.type &&
            Objects.equals(entityId, that.entityId) &&
            Objects.equals(instanceId, that.instanceId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, entityId, instanceId, timestamp);
    }

    @Override
    public String toString() {
        return "InvalidationEvent{" +
            "type=" + type +
            ", entityId=" + entityId +
            ", instanceId='" + instanceId + '\'' +
            ", timestamp=" + timestamp +
            '}';
    }
}
