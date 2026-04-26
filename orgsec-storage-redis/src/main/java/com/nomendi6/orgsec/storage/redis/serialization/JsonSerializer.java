package com.nomendi6.orgsec.storage.redis.serialization;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nomendi6.orgsec.storage.redis.resilience.CacheSerializationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Generic JSON serializer for domain objects.
 * <p>
 * Handles serialization and deserialization of domain objects (PersonDef, OrganizationDef, RoleDef)
 * to/from JSON format using Jackson ObjectMapper.
 * </p>
 *
 * @param <T> the type of object to serialize/deserialize
 */
public class JsonSerializer<T> {

    private static final Logger log = LoggerFactory.getLogger(JsonSerializer.class);
    private final ObjectMapper objectMapper;
    private final Class<T> targetType;

    /**
     * Constructs a new JSON serializer for the specified type using provided ObjectMapper.
     * <p>
     * This is the preferred constructor that uses a shared, centrally configured ObjectMapper.
     * </p>
     *
     * @param targetType   the class of the type to serialize/deserialize
     * @param objectMapper the configured ObjectMapper instance
     */
    public JsonSerializer(Class<T> targetType, ObjectMapper objectMapper) {
        this.targetType = targetType;
        this.objectMapper = objectMapper;
        log.debug("Created JsonSerializer for {} with provided ObjectMapper", targetType.getSimpleName());
    }

    /**
     * Constructs a new JSON serializer for the specified type.
     * <p>
     * This constructor creates its own ObjectMapper using OrgsecObjectMapperFactory.
     * Prefer using {@link #JsonSerializer(Class, ObjectMapper)} with a shared ObjectMapper
     * for better consistency across the application.
     * </p>
     *
     * @param targetType the class of the type to serialize/deserialize
     * @deprecated Use {@link #JsonSerializer(Class, ObjectMapper)} instead
     */
    @Deprecated
    public JsonSerializer(Class<T> targetType) {
        this.targetType = targetType;
        this.objectMapper = new OrgsecObjectMapperFactory().createObjectMapper();
        log.debug("Created JsonSerializer for {} with default ObjectMapper (deprecated constructor)", targetType.getSimpleName());
    }

    /**
     * Serializes an object to JSON string.
     *
     * @param object the object to serialize
     * @return JSON string representation
     * @throws CacheSerializationException if serialization fails
     */
    public String serialize(T object) {
        if (object == null) {
            return null;
        }

        try {
            return objectMapper.writeValueAsString(object);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize object of type {}: {}", targetType.getSimpleName(), e.getMessage());
            throw new CacheSerializationException(
                "Failed to serialize " + targetType.getSimpleName(),
                e
            );
        }
    }

    /**
     * Deserializes JSON string to an object.
     *
     * @param json the JSON string
     * @return deserialized object
     * @throws CacheSerializationException if deserialization fails
     */
    public T deserialize(String json) {
        if (json == null || json.trim().isEmpty()) {
            return null;
        }

        try {
            return objectMapper.readValue(json, targetType);
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize JSON to {}: {}", targetType.getSimpleName(), e.getMessage());
            throw new CacheSerializationException(
                "Failed to deserialize " + targetType.getSimpleName(),
                json,
                targetType,
                e
            );
        }
    }

    /**
     * Returns the target type class.
     *
     * @return the target type
     */
    public Class<T> getTargetType() {
        return targetType;
    }

    /**
     * Returns the configured ObjectMapper.
     *
     * @return the ObjectMapper instance
     */
    public ObjectMapper getObjectMapper() {
        return objectMapper;
    }
}
