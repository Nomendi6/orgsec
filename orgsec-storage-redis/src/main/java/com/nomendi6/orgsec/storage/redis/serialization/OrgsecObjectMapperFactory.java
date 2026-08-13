package com.nomendi6.orgsec.storage.redis.serialization;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.nomendi6.orgsec.model.PersonDef;
import com.nomendi6.orgsec.storage.redis.config.RedisStorageProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Factory for creating configured ObjectMapper instances for OrgSec library.
 * <p>
 * Provides centralized ObjectMapper configuration to ensure consistent
 * serialization/deserialization behavior across all OrgSec components.
 * </p>
 * <p>
 * This factory creates ObjectMapper instances that are isolated from the
 * application's global ObjectMapper to prevent configuration conflicts.
 * </p>
 */
public class OrgsecObjectMapperFactory {

    private static final Logger log = LoggerFactory.getLogger(OrgsecObjectMapperFactory.class);

    private final RedisStorageProperties.SerializationConfig config;

    // Cached ObjectMapper instances - created lazily and reused
    private volatile ObjectMapper domainObjectMapper;
    private volatile ObjectMapper eventObjectMapper;
    private final Object lock = new Object();

    /**
     * Creates factory with default configuration.
     */
    public OrgsecObjectMapperFactory() {
        this.config = new RedisStorageProperties.SerializationConfig();
    }

    /**
     * Creates factory with specified configuration.
     *
     * @param config the serialization configuration
     */
    public OrgsecObjectMapperFactory(RedisStorageProperties.SerializationConfig config) {
        this.config = config != null ? config : new RedisStorageProperties.SerializationConfig();
    }

    /**
     * Returns the shared ObjectMapper for domain objects.
     * <p>
     * This method returns a cached instance that is thread-safe and reused
     * across all OrgSec components. The ObjectMapper is created lazily on
     * first access.
     * </p>
     *
     * @return shared ObjectMapper for domain objects
     */
    public ObjectMapper getDomainObjectMapper() {
        if (domainObjectMapper == null) {
            synchronized (lock) {
                if (domainObjectMapper == null) {
                    if (config.isStrictMode()) {
                        log.info("Creating secure OrgSec ObjectMapper (strict mode enabled)");
                        domainObjectMapper = createSecureObjectMapper();
                    } else {
                        log.info("Creating standard OrgSec ObjectMapper");
                        domainObjectMapper = createObjectMapper();
                    }
                }
            }
        }
        return domainObjectMapper;
    }

    /**
     * Returns the shared ObjectMapper for event serialization.
     * <p>
     * This method returns a cached instance that is thread-safe and reused
     * across all OrgSec components. The ObjectMapper is created lazily on
     * first access.
     * </p>
     *
     * @return shared ObjectMapper for events
     */
    public ObjectMapper getEventObjectMapper() {
        if (eventObjectMapper == null) {
            synchronized (lock) {
                if (eventObjectMapper == null) {
                    log.info("Creating OrgSec event ObjectMapper");
                    eventObjectMapper = createSimpleObjectMapper();
                }
            }
        }
        return eventObjectMapper;
    }

    /**
     * Creates a fully configured ObjectMapper for OrgSec domain objects.
     * <p>
     * Configuration includes:
     * <ul>
     *   <li>NON_NULL inclusion - null fields are excluded from JSON</li>
     *   <li>FAIL_ON_UNKNOWN_PROPERTIES disabled - for forward compatibility</li>
     *   <li>Java time values - ISO 8601 date/time format</li>
     *   <li>FIELD visibility - direct field access for domain objects</li>
     *   <li>PersonDefMixin - constructor-based deserialization for PersonDef</li>
     * </ul>
     * </p>
     *
     * @return configured ObjectMapper instance
     */
    public ObjectMapper createObjectMapper() {
        log.debug("Creating OrgSec ObjectMapper with standard configuration");

        JsonMapper.Builder builder = JsonMapper.builder()
            .changeDefaultPropertyInclusion(value -> JsonInclude.Value.construct(JsonInclude.Include.NON_NULL, JsonInclude.Include.NON_NULL))
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, config.isFailOnUnknownProperties())
            .disable(SerializationFeature.INDENT_OUTPUT)
            .changeDefaultVisibility(visibility -> visibility
                .withVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.NONE)
                .withVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY))
            .addMixIn(PersonDef.class, PersonDefMixin.class);

        ObjectMapper mapper = builder.build();

        if (config.isFailOnUnknownProperties()) {
            log.info("OrgSec ObjectMapper configured with FAIL_ON_UNKNOWN_PROPERTIES=true (strict mode)");
        }

        return mapper;
    }

    /**
     * Creates a secure ObjectMapper with stricter settings for production environments.
     * <p>
     * This variant applies additional security restrictions:
     * <ul>
     *   <li>FAIL_ON_UNKNOWN_PROPERTIES enabled - reject unexpected fields</li>
     *   <li>FAIL_ON_NULL_FOR_PRIMITIVES enabled - prevent null in primitive fields</li>
     *   <li>FAIL_ON_READING_DUP_TREE_KEY enabled - reject duplicate JSON keys</li>
     *   <li>FAIL_ON_NUMBERS_FOR_ENUMS enabled - prevent numeric enum values</li>
     * </ul>
     * </p>
     * <p>
     * Use this when security is more important than forward compatibility.
     * </p>
     *
     * @return secure ObjectMapper instance
     */
    public ObjectMapper createSecureObjectMapper() {
        log.info("Creating secure OrgSec ObjectMapper with strict configuration");

        return JsonMapper.builder()
            .changeDefaultPropertyInclusion(value -> JsonInclude.Value.construct(JsonInclude.Include.NON_NULL, JsonInclude.Include.NON_NULL))
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            // Jackson 3 moved this out of DeserializationFeature into EnumFeature; it stays
            // enabled here so numeric enum values are rejected instead of coerced by ordinal.
            .enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
            .disable(SerializationFeature.INDENT_OUTPUT)
            .changeDefaultVisibility(visibility -> visibility
                .withVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.NONE)
                .withVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY))
            .addMixIn(PersonDef.class, PersonDefMixin.class)
            .build();
    }

    /**
     * Creates a simple ObjectMapper for basic JSON operations.
     * <p>
     * This variant does not include domain-specific configurations like
     * MixIns or field visibility changes. Suitable for generic JSON
     * operations like event serialization.
     * </p>
     *
     * @return simple ObjectMapper instance
     */
    public ObjectMapper createSimpleObjectMapper() {
        log.debug("Creating simple ObjectMapper for basic operations");

        return JsonMapper.builder().build();
    }

    /**
     * Returns the current serialization configuration.
     *
     * @return the serialization configuration
     */
    public RedisStorageProperties.SerializationConfig getConfig() {
        return config;
    }
}
