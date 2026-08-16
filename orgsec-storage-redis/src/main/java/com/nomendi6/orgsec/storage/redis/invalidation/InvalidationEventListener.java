package com.nomendi6.orgsec.storage.redis.invalidation;

import com.nomendi6.orgsec.storage.redis.cache.L1Cache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import tools.jackson.databind.ObjectMapper;

/**
 * Listener for cache invalidation events from Redis Pub/Sub.
 * <p>
 * Listens for invalidation events published by other application instances
 * and triggers L1 cache eviction accordingly.
 * </p>
 */
public class InvalidationEventListener implements MessageListener {

    private static final Logger log = LoggerFactory.getLogger(InvalidationEventListener.class);

    private final L1Cache<Long, ?> personCache;
    private final L1Cache<Long, ?> organizationCache;
    private final L1Cache<Long, ?> roleCache;
    private final L1Cache<Long, ?> positionRoleCache;
    private final L1Cache<String, ?> privilegeCache;
    private final String instanceId;
    private final ObjectMapper objectMapper;

    /**
     * Constructs a new invalidation event listener with provided ObjectMapper.
     *
     * @param personCache       the person L1 cache
     * @param organizationCache the organization L1 cache
     * @param roleCache         the role L1 cache
     * @param instanceId        the UUID of this application instance
     * @param objectMapper      the ObjectMapper for JSON deserialization
     */
    public InvalidationEventListener(
        L1Cache<Long, ?> personCache,
        L1Cache<Long, ?> organizationCache,
        L1Cache<Long, ?> roleCache,
        String instanceId,
        ObjectMapper objectMapper
    ) {
        this(personCache, organizationCache, roleCache, roleCache, null, instanceId, objectMapper);
    }

    /**
     * Constructs a listener with distinct party/position role caches and the string-keyed
     * privilege cache.
     */
    public InvalidationEventListener(
        L1Cache<Long, ?> personCache,
        L1Cache<Long, ?> organizationCache,
        L1Cache<Long, ?> partyRoleCache,
        L1Cache<Long, ?> positionRoleCache,
        L1Cache<String, ?> privilegeCache,
        String instanceId,
        ObjectMapper objectMapper
    ) {
        this.personCache = personCache;
        this.organizationCache = organizationCache;
        this.roleCache = partyRoleCache;
        this.positionRoleCache = positionRoleCache;
        this.privilegeCache = privilegeCache;
        this.instanceId = instanceId;
        this.objectMapper = objectMapper;
    }

    /**
     * Constructs a new invalidation event listener.
     *
     * @param personCache       the person L1 cache
     * @param organizationCache the organization L1 cache
     * @param roleCache         the role L1 cache
     * @param instanceId        the UUID of this application instance
     * @deprecated Use {@link #InvalidationEventListener(L1Cache, L1Cache, L1Cache, String, ObjectMapper)} instead
     */
    @Deprecated
    public InvalidationEventListener(
        L1Cache<Long, ?> personCache,
        L1Cache<Long, ?> organizationCache,
        L1Cache<Long, ?> roleCache,
        String instanceId
    ) {
        this(personCache, organizationCache, roleCache, instanceId, new ObjectMapper());
    }

    /**
     * Handles incoming Pub/Sub messages.
     *
     * @param message the message
     * @param pattern the subscription pattern
     */
    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            String json = new String(message.getBody());
            InvalidationEvent event = objectMapper.readValue(json, InvalidationEvent.class);

            // Ignore own events (deduplication)
            if (event.getInstanceId().equals(instanceId)) {
                log.trace("Ignoring own invalidation event: {}", event);
                return;
            }

            log.debug("Received invalidation event: {}", event);

            // Process event based on type
            processEvent(event);

        } catch (Exception e) {
            log.error("Failed to process invalidation event", e);
            // Don't throw - continue processing other events
        }
    }

    /**
     * Processes an invalidation event.
     *
     * @param event the event to process
     */
    private void processEvent(InvalidationEvent event) {
        switch (event.getType()) {
            case PERSON_CHANGED:
                if (event.getEntityId() != null) {
                    personCache.evict(event.getEntityId());
                    log.debug("Evicted person from L1 cache: {}", event.getEntityId());
                }
                break;

            case ORG_CHANGED:
                if (event.getEntityId() != null) {
                    organizationCache.evict(event.getEntityId());
                    log.debug("Evicted organization from L1 cache: {}", event.getEntityId());
                }
                break;

            case ROLE_CHANGED:
                if (event.getEntityId() != null) {
                    roleCache.evict(event.getEntityId());
                    if (positionRoleCache != roleCache) {
                        positionRoleCache.evict(event.getEntityId());
                    }
                    log.debug("Evicted role from L1 cache: {}", event.getEntityId());
                }
                break;

            case PRIVILEGE_CHANGED:
                if (privilegeCache != null) {
                    // Legacy events expose a numeric ID while privileges use stable string IDs.
                    privilegeCache.clear();
                }
                log.debug("Privilege changed event received - cleared privilege L1 cache");
                break;

            case SECURITY_REFRESH:
                log.info("Security refresh event received - clearing all L1 caches");
                personCache.clear();
                organizationCache.clear();
                roleCache.clear();
                if (positionRoleCache != roleCache) {
                    positionRoleCache.clear();
                }
                if (privilegeCache != null) {
                    privilegeCache.clear();
                }
                break;

            default:
                log.warn("Unknown invalidation event type: {}", event.getType());
        }
    }

    /**
     * Returns the instance ID.
     *
     * @return the instance ID
     */
    public String getInstanceId() {
        return instanceId;
    }
}
