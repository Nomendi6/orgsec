package com.nomendi6.orgsec.storage.redis.health;

import com.nomendi6.orgsec.storage.redis.resilience.RedisStorageNotReadyException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * Spring Boot Actuator health indicator for Redis storage.
 * <p>
 * Checks Redis connection status and reports health information
 * via the /actuator/health endpoint.
 * </p>
 */
public class RedisStorageHealthIndicator implements HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(RedisStorageHealthIndicator.class);

    private final RedisTemplate<String, String> redisTemplate;
    private final BooleanSupplier authorizationReady;

    /**
     * Constructs a new Redis storage health indicator.
     *
     * @param redisTemplate the Redis template
     */
    public RedisStorageHealthIndicator(RedisTemplate<String, String> redisTemplate) {
        this(redisTemplate, () -> true);
    }

    /**
     * Constructs an indicator that distinguishes Redis liveness from authorization readiness.
     *
     * @param redisTemplate Redis connection template
     * @param authorizationReady verified-snapshot readiness probe
     */
    public RedisStorageHealthIndicator(
            RedisTemplate<String, String> redisTemplate,
            BooleanSupplier authorizationReady) {
        this.redisTemplate = redisTemplate;
        this.authorizationReady = Objects.requireNonNull(
            authorizationReady,
            "authorizationReady must not be null"
        );
    }

    /**
     * Performs health check.
     *
     * @return health status
     */
    @Override
    public Health health() {
        try {
            // Ping Redis to check connection
            RedisConnection connection = redisTemplate.getConnectionFactory().getConnection();
            String pong = connection.ping();
            connection.close();

            if ("PONG".equalsIgnoreCase(pong)) {
                if (!authorizationReady.getAsBoolean()) {
                    return Health.outOfService()
                        .withDetail("connection", "active")
                        .withDetail("response", pong)
                        .withDetail("authorization", "not-ready")
                        .withDetail(
                            "diagnostic",
                            RedisStorageNotReadyException.DIAGNOSTIC_CODE
                        )
                        .build();
                }
                return Health.up()
                    .withDetail("connection", "active")
                    .withDetail("response", pong)
                    .withDetail("authorization", "ready")
                    .build();
            } else {
                return Health.down()
                    .withDetail("connection", "failed")
                    .withDetail("response", pong)
                    .build();
            }

        } catch (Exception e) {
            log.error("Redis health check failed", e);
            return Health.down()
                .withDetail("connection", "failed")
                .withDetail("error", e.getClass().getSimpleName())
                .build();
        }
    }
}
