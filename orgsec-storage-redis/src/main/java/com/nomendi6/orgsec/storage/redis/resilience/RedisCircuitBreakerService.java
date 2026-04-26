package com.nomendi6.orgsec.storage.redis.resilience;

import com.nomendi6.orgsec.storage.redis.config.RedisStorageProperties;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * Service providing Circuit Breaker functionality for Redis operations.
 * <p>
 * Wraps Redis operations with Resilience4j circuit breaker to prevent
 * cascading failures when Redis is unavailable.
 * </p>
 */
public class RedisCircuitBreakerService {

    private static final Logger log = LoggerFactory.getLogger(RedisCircuitBreakerService.class);
    private static final String CIRCUIT_BREAKER_NAME = "redisStorage";

    private final CircuitBreaker circuitBreaker;
    private final boolean enabled;

    /**
     * Constructs a new circuit breaker service.
     *
     * @param properties the Redis storage properties containing circuit breaker config
     */
    public RedisCircuitBreakerService(RedisStorageProperties properties) {
        RedisStorageProperties.CircuitBreakerConfig cbConfig = properties.getCircuitBreaker();
        this.enabled = cbConfig.isEnabled();

        if (enabled) {
            CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .failureRateThreshold(cbConfig.getFailureThreshold())
                .waitDurationInOpenState(Duration.ofMillis(cbConfig.getWaitDuration()))
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(cbConfig.getSlidingWindowSize())
                .minimumNumberOfCalls(cbConfig.getMinimumCalls())
                .permittedNumberOfCallsInHalfOpenState(cbConfig.getPermittedCallsInHalfOpen())
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .recordExceptions(
                    RedisConnectionException.class,
                    io.lettuce.core.RedisException.class,
                    io.lettuce.core.RedisConnectionException.class
                )
                .build();

            CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(config);
            this.circuitBreaker = registry.circuitBreaker(CIRCUIT_BREAKER_NAME);

            // Register event listeners for monitoring
            circuitBreaker.getEventPublisher()
                .onStateTransition(event ->
                    log.info("Circuit breaker '{}' state transition: {} -> {}",
                        CIRCUIT_BREAKER_NAME,
                        event.getStateTransition().getFromState(),
                        event.getStateTransition().getToState()))
                .onFailureRateExceeded(event ->
                    log.warn("Circuit breaker '{}' failure rate exceeded: {}%",
                        CIRCUIT_BREAKER_NAME,
                        event.getFailureRate()))
                .onCallNotPermitted(event ->
                    log.debug("Circuit breaker '{}' call not permitted (circuit open)",
                        CIRCUIT_BREAKER_NAME));

            log.info("Circuit breaker '{}' initialized with failure threshold: {}%, wait duration: {}ms",
                CIRCUIT_BREAKER_NAME, cbConfig.getFailureThreshold(), cbConfig.getWaitDuration());
        } else {
            this.circuitBreaker = null;
            log.info("Circuit breaker disabled");
        }
    }

    /**
     * Executes a supplier with circuit breaker protection.
     *
     * @param supplier the operation to execute
     * @param fallback the fallback value if circuit is open or operation fails
     * @param <T>      the return type
     * @return the result or fallback value
     */
    public <T> T executeWithFallback(Supplier<T> supplier, T fallback) {
        if (!enabled || circuitBreaker == null) {
            return supplier.get();
        }

        try {
            return circuitBreaker.executeSupplier(supplier);
        } catch (io.github.resilience4j.circuitbreaker.CallNotPermittedException e) {
            log.debug("Circuit breaker open - returning fallback");
            return fallback;
        } catch (Exception e) {
            log.warn("Redis operation failed: {}", e.getMessage());
            return fallback;
        }
    }

    /**
     * Executes a runnable with circuit breaker protection.
     *
     * @param runnable the operation to execute
     */
    public void executeWithFallback(Runnable runnable) {
        if (!enabled || circuitBreaker == null) {
            runnable.run();
            return;
        }

        try {
            circuitBreaker.executeRunnable(runnable);
        } catch (io.github.resilience4j.circuitbreaker.CallNotPermittedException e) {
            log.debug("Circuit breaker open - skipping operation");
        } catch (Exception e) {
            log.warn("Redis operation failed: {}", e.getMessage());
        }
    }

    /**
     * Returns the current state of the circuit breaker.
     *
     * @return the circuit breaker state, or "DISABLED" if not enabled
     */
    public String getState() {
        if (!enabled || circuitBreaker == null) {
            return "DISABLED";
        }
        return circuitBreaker.getState().name();
    }

    /**
     * Returns circuit breaker metrics.
     *
     * @return metrics summary string
     */
    public String getMetrics() {
        if (!enabled || circuitBreaker == null) {
            return "Circuit breaker disabled";
        }

        CircuitBreaker.Metrics metrics = circuitBreaker.getMetrics();
        return String.format(
            "State: %s, Failure rate: %.2f%%, Calls: %d (failed: %d), Buffered: %d",
            circuitBreaker.getState(),
            metrics.getFailureRate(),
            metrics.getNumberOfSuccessfulCalls() + metrics.getNumberOfFailedCalls(),
            metrics.getNumberOfFailedCalls(),
            metrics.getNumberOfBufferedCalls()
        );
    }

    /**
     * Returns whether the circuit breaker is enabled.
     *
     * @return true if enabled
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Returns the underlying circuit breaker instance.
     *
     * @return the circuit breaker, or null if disabled
     */
    public CircuitBreaker getCircuitBreaker() {
        return circuitBreaker;
    }
}
