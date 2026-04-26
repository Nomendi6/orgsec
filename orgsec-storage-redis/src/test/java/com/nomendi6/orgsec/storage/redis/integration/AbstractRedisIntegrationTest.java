package com.nomendi6.orgsec.storage.redis.integration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Base class for Redis integration tests using Testcontainers.
 * <p>
 * Provides a shared Redis container instance for all integration tests.
 * The container is started once before all tests and stopped after all tests complete.
 * </p>
 */
@Testcontainers
public abstract class AbstractRedisIntegrationTest {

    protected static final String REDIS_IMAGE = "redis:8.4.0-alpine";
    protected static final int REDIS_PORT = 6379;

    @Container
    protected static final GenericContainer<?> redisContainer = new GenericContainer<>(
        DockerImageName.parse(REDIS_IMAGE)
    ).withExposedPorts(REDIS_PORT);

    protected static RedisConnectionFactory redisConnectionFactory;
    protected static RedisTemplate<String, String> redisTemplate;

    @BeforeAll
    static void setUpRedis() {
        // Create Redis connection factory
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration();
        config.setHostName(redisContainer.getHost());
        config.setPort(redisContainer.getMappedPort(REDIS_PORT));

        redisConnectionFactory = new LettuceConnectionFactory(config);
        ((LettuceConnectionFactory) redisConnectionFactory).afterPropertiesSet();

        // Create RedisTemplate
        redisTemplate = new RedisTemplate<>();
        redisTemplate.setConnectionFactory(redisConnectionFactory);
        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        redisTemplate.setKeySerializer(stringSerializer);
        redisTemplate.setValueSerializer(stringSerializer);
        redisTemplate.setHashKeySerializer(stringSerializer);
        redisTemplate.setHashValueSerializer(stringSerializer);
        redisTemplate.afterPropertiesSet();
    }

    @AfterAll
    static void tearDownRedis() {
        if (redisConnectionFactory != null) {
            ((LettuceConnectionFactory) redisConnectionFactory).destroy();
        }
    }

    /**
     * Clears all data from Redis before each test.
     * Subclasses can call this in their @BeforeEach method.
     */
    protected void clearRedis() {
        redisTemplate.getConnectionFactory()
            .getConnection()
            .serverCommands()
            .flushAll();
    }

    /**
     * Get Redis host for container.
     */
    protected static String getRedisHost() {
        return redisContainer.getHost();
    }

    /**
     * Get Redis port for container.
     */
    protected static int getRedisPort() {
        return redisContainer.getMappedPort(REDIS_PORT);
    }
}
