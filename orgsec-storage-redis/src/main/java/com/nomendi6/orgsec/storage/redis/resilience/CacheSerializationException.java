package com.nomendi6.orgsec.storage.redis.resilience;

/**
 * Exception thrown when JSON serialization or deserialization fails.
 * <p>
 * This exception indicates issues with converting domain objects to/from JSON,
 * such as malformed JSON, missing fields, or incompatible data types.
 * When this exception is thrown during deserialization, the corrupted cache
 * entry should be evicted and data should be reloaded from the database.
 * </p>
 */
public class CacheSerializationException extends RedisStorageException {

    private final String json;
    private final Class<?> targetType;

    /**
     * Constructs a new cache serialization exception.
     *
     * @param message the detail message
     * @param cause   the cause of this exception
     */
    public CacheSerializationException(String message, Throwable cause) {
        super(message, cause);
        this.json = null;
        this.targetType = null;
    }

    /**
     * Constructs a new cache serialization exception with JSON and target type information.
     *
     * @param message    the detail message
     * @param json       the JSON string that failed to serialize/deserialize
     * @param targetType the target class type
     * @param cause      the cause of this exception
     */
    public CacheSerializationException(String message, String json, Class<?> targetType, Throwable cause) {
        super(message, cause);
        this.json = json;
        this.targetType = targetType;
    }

    /**
     * Returns the JSON string that failed to serialize/deserialize.
     *
     * @return the JSON string, or null if not available
     */
    public String getJson() {
        return json;
    }

    /**
     * Returns the target class type.
     *
     * @return the target type, or null if not available
     */
    public Class<?> getTargetType() {
        return targetType;
    }

    @Override
    public String toString() {
        if (targetType != null) {
            return String.format("CacheSerializationException: %s [targetType=%s, json=%s]",
                getMessage(), targetType.getSimpleName(),
                json != null && json.length() > 100 ? json.substring(0, 100) + "..." : json);
        }
        return super.toString();
    }
}
