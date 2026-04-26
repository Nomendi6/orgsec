package com.nomendi6.orgsec.storage.redis.resilience;

import com.nomendi6.orgsec.storage.redis.config.RedisStorageProperties;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class RedisCircuitBreakerServiceTest {

    @Test
    void shouldExecuteDirectlyWhenDisabled() {
        RedisStorageProperties properties = new RedisStorageProperties();
        properties.getCircuitBreaker().setEnabled(false);
        RedisCircuitBreakerService service = new RedisCircuitBreakerService(properties);
        AtomicBoolean ran = new AtomicBoolean(false);

        String value = service.executeWithFallback(() -> "value", "fallback");
        service.executeWithFallback(() -> ran.set(true));

        assertThat(value).isEqualTo("value");
        assertThat(ran).isTrue();
        assertThat(service.isEnabled()).isFalse();
        assertThat(service.getState()).isEqualTo("DISABLED");
        assertThat(service.getMetrics()).isEqualTo("Circuit breaker disabled");
        assertThat(service.getCircuitBreaker()).isNull();
    }

    @Test
    void shouldInitializeEnabledCircuitBreaker() {
        RedisCircuitBreakerService service = new RedisCircuitBreakerService(propertiesForFastOpeningBreaker());

        assertThat(service.isEnabled()).isTrue();
        assertThat(service.getCircuitBreaker()).isNotNull();
        assertThat(service.getState()).isEqualTo("CLOSED");
    }

    @Test
    void shouldReturnFallbackWhenSupplierFails() {
        RedisCircuitBreakerService service = new RedisCircuitBreakerService(propertiesForFastOpeningBreaker());

        String result = service.executeWithFallback(() -> {
            throw new RedisConnectionException("down", new RuntimeException("boom"));
        }, "fallback");

        assertThat(result).isEqualTo("fallback");
    }

    @Test
    void shouldSwallowRunnableFailure() {
        RedisCircuitBreakerService service = new RedisCircuitBreakerService(propertiesForFastOpeningBreaker());

        service.executeWithFallback(() -> {
            throw new RedisConnectionException("down", new RuntimeException("boom"));
        });

        assertThat(service.getMetrics()).contains("State:");
    }

    @Test
    void shouldOpenCircuitAndReturnFallbackWhenCallIsNotPermitted() {
        RedisCircuitBreakerService service = new RedisCircuitBreakerService(propertiesForFastOpeningBreaker());

        service.executeWithFallback(() -> {
            throw new RedisConnectionException("down-1", new RuntimeException("boom"));
        }, "fallback");
        service.executeWithFallback(() -> {
            throw new RedisConnectionException("down-2", new RuntimeException("boom"));
        }, "fallback");

        String result = service.executeWithFallback(() -> "should-not-run", "open-fallback");

        assertThat(service.getState()).isEqualTo("OPEN");
        assertThat(result).isEqualTo("open-fallback");
        assertThat(service.getMetrics()).contains("State: OPEN", "Calls:", "Buffered:");
    }

    private RedisStorageProperties propertiesForFastOpeningBreaker() {
        RedisStorageProperties properties = new RedisStorageProperties();
        RedisStorageProperties.CircuitBreakerConfig config = properties.getCircuitBreaker();
        config.setEnabled(true);
        config.setFailureThreshold(50);
        config.setMinimumCalls(2);
        config.setSlidingWindowSize(2);
        config.setPermittedCallsInHalfOpen(1);
        config.setWaitDuration(1000);
        return properties;
    }
}
