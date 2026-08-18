package com.nomendi6.orgsec.storage.redis.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.nomendi6.orgsec.storage.redis.RedisSecurityDataStorage;
import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Primary;

/**
 * Which bean carries {@code @Primary} in a Redis deployment, and what that bean returns.
 *
 * <p>Up to 1.0.4 the marker sat on the {@code RedisSecurityDataStorage} implementation itself, so
 * enabling Redis and JWT together produced two primaries. It now sits on a separate alias that is
 * registered only while JWT is off - and the alias must hand back the very same instance, because
 * invalidation callbacks and the health indicator hold references to it.
 */
class RedisStoragePrimaryAliasTest {

    @Test
    void primaryAliasReturnsTheRedisStorageItselfNotADecorator() {
        RedisStorageAutoConfiguration configuration = new RedisStorageAutoConfiguration();
        RedisSecurityDataStorage storage = mock(RedisSecurityDataStorage.class);

        assertThat(configuration.orgsecPrimaryStorage(storage)).isSameAs(storage);
    }

    @Test
    void primaryMarkerSitsOnTheAliasAndNotOnTheImplementationBean() {
        assertThat(isPrimary("redisSecurityDataStorage")).isFalse();
        assertThat(isPrimary("orgsecPrimaryStorage")).isTrue();
    }

    private boolean isPrimary(String beanMethodName) {
        Method method = Arrays.stream(RedisStorageAutoConfiguration.class.getDeclaredMethods())
            .filter(candidate -> candidate.getName().equals(beanMethodName))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no bean method named " + beanMethodName));
        return method.isAnnotationPresent(Primary.class);
    }
}
