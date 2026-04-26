package com.nomendi6.orgsec.storage.redis.invalidation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Publisher for cache invalidation events via Redis Pub/Sub.
 * <p>
 * Publishes invalidation events when cached data changes, allowing
 * all application instances to invalidate their local caches.
 * </p>
 */
public class InvalidationEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(InvalidationEventPublisher.class);

    private final RedisTemplate<String, String> redisTemplate;
    private final String channel;
    private final boolean async;
    private final String instanceId;
    private final ObjectMapper objectMapper;

    /**
     * Constructs a new invalidation event publisher with provided ObjectMapper.
     *
     * @param redisTemplate the Redis template
     * @param channel       the Pub/Sub channel name
     * @param async         whether to publish asynchronously
     * @param instanceId    the UUID of this application instance
     * @param objectMapper  the ObjectMapper for JSON serialization
     */
    public InvalidationEventPublisher(
        RedisTemplate<String, String> redisTemplate,
        String channel,
        boolean async,
        String instanceId,
        ObjectMapper objectMapper
    ) {
        this.redisTemplate = redisTemplate;
        this.channel = channel;
        this.async = async;
        this.instanceId = instanceId != null ? instanceId : UUID.randomUUID().toString();
        this.objectMapper = objectMapper;
    }

    /**
     * Constructs a new invalidation event publisher.
     *
     * @param redisTemplate the Redis template
     * @param channel       the Pub/Sub channel name
     * @param async         whether to publish asynchronously
     * @param instanceId    the UUID of this application instance
     * @deprecated Use {@link #InvalidationEventPublisher(RedisTemplate, String, boolean, String, ObjectMapper)} instead
     */
    @Deprecated
    public InvalidationEventPublisher(
        RedisTemplate<String, String> redisTemplate,
        String channel,
        boolean async,
        String instanceId
    ) {
        this(redisTemplate, channel, async, instanceId, new ObjectMapper());
    }

    /**
     * Publishes a person changed event.
     *
     * @param userId the user ID that changed
     */
    public void publishPersonChanged(Long userId) {
        publishEvent(InvalidationType.PERSON_CHANGED, userId);
    }

    /**
     * Publishes an organization changed event.
     *
     * @param orgId the organization ID that changed
     */
    public void publishOrganizationChanged(Long orgId) {
        publishEvent(InvalidationType.ORG_CHANGED, orgId);
    }

    /**
     * Publishes a role changed event.
     *
     * @param roleId the role ID that changed
     */
    public void publishRoleChanged(Long roleId) {
        publishEvent(InvalidationType.ROLE_CHANGED, roleId);
    }

    /**
     * Publishes a privilege changed event.
     *
     * @param privilegeId the privilege ID that changed
     */
    public void publishPrivilegeChanged(Long privilegeId) {
        publishEvent(InvalidationType.PRIVILEGE_CHANGED, privilegeId);
    }

    /**
     * Publishes a security refresh event (full cache refresh).
     */
    public void publishSecurityRefresh() {
        publishEvent(InvalidationType.SECURITY_REFRESH, null);
    }

    /**
     * Publishes an invalidation event to Redis Pub/Sub.
     *
     * @param type     the invalidation type
     * @param entityId the entity ID (can be null for SECURITY_REFRESH)
     */
    private void publishEvent(InvalidationType type, Long entityId) {
        InvalidationEvent event = new InvalidationEvent(
            type,
            entityId,
            instanceId,
            System.currentTimeMillis()
        );

        try {
            String json = objectMapper.writeValueAsString(event);

            if (async) {
                publishAsync(json, event);
            } else {
                publishSync(json, event);
            }

        } catch (JsonProcessingException e) {
            log.error("Failed to serialize invalidation event: {}", event, e);
            // Don't throw - invalidation is not critical
        }
    }

    /**
     * Publishes event synchronously.
     *
     * @param json  the JSON representation of the event
     * @param event the event object (for logging)
     */
    private void publishSync(String json, InvalidationEvent event) {
        try {
            redisTemplate.convertAndSend(channel, json);
            log.debug("Published invalidation event: {}", event);

        } catch (Exception e) {
            log.warn("Failed to publish invalidation event: {}", event, e);
            // Don't throw - L2 cache is already updated
        }
    }

    /**
     * Publishes event asynchronously.
     *
     * @param json  the JSON representation of the event
     * @param event the event object (for logging)
     */
    private void publishAsync(String json, InvalidationEvent event) {
        CompletableFuture.runAsync(() -> {
            try {
                redisTemplate.convertAndSend(channel, json);
                log.debug("Published invalidation event (async): {}", event);

            } catch (Exception e) {
                log.warn("Failed to publish invalidation event (async): {}", event, e);
                // Fire-and-forget - no error feedback
            }
        });
    }

    /**
     * Returns the instance ID.
     *
     * @return the instance ID
     */
    public String getInstanceId() {
        return instanceId;
    }

    /**
     * Returns the Pub/Sub channel name.
     *
     * @return the channel name
     */
    public String getChannel() {
        return channel;
    }

    /**
     * Returns whether async publishing is enabled.
     *
     * @return true if async
     */
    public boolean isAsync() {
        return async;
    }
}
