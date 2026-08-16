package com.nomendi6.orgsec.storage.redis.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.nomendi6.orgsec.storage.SecurityDataStorage;
import com.nomendi6.orgsec.storage.redis.RedisSecurityDataStorage;
import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisConnectionFactory;

class RedisStoragePrimaryAliasTest {

    @Test
    void primaryAliasReturnsTheRedisImplementationObject() {
        RedisStorageAutoConfiguration configuration = new RedisStorageAutoConfiguration();
        RedisSecurityDataStorage storage = mock(RedisSecurityDataStorage.class);

        assertThat(configuration.orgsecPrimaryStorage(storage)).isSameAs(storage);
    }

    @Test
    void primaryMarkerLivesOnlyOnTheConditionalAlias() {
        assertThat(isPrimary("redisSecurityDataStorage")).isFalse();
        assertThat(isPrimary("orgsecPrimaryStorage")).isTrue();
    }

    @Test
    void autoConfigurationOwnsTheOrgsecConnectionFactoryBeforeBootRedisDefaults() {
        Import importedConfiguration = RedisStorageAutoConfiguration.class.getAnnotation(Import.class);
        AutoConfiguration ordering = RedisStorageAutoConfiguration.class.getAnnotation(AutoConfiguration.class);

        assertThat(importedConfiguration.value()).contains(LettucePoolConfiguration.class);
        assertThat(ordering.beforeName())
            .contains(
                "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration",
                "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration"
            );
    }

    @Test
    void autoConfigurationWiresDistinctTypedRoleCachesAndOnePrimaryRedisObject() {
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RedisStorageAutoConfiguration.class))
            .withBean(RedisConnectionFactory.class, () -> mock(RedisConnectionFactory.class))
            .withPropertyValues(
                "orgsec.storage.redis.enabled=true",
                "orgsec.storage.features.redis-enabled=true"
            )
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean("roleL1Cache"))
                    .isNotSameAs(context.getBean("positionRoleL1Cache"));

                RedisSecurityDataStorage implementation = context.getBean(RedisSecurityDataStorage.class);
                assertThat(context.getBean(SecurityDataStorage.class)).isSameAs(implementation);
                assertThat(context.getBean("orgsecPrimaryStorage")).isSameAs(implementation);
            });
    }

    private static boolean isPrimary(String methodName) {
        Method method = Arrays.stream(RedisStorageAutoConfiguration.class.getDeclaredMethods())
            .filter(candidate -> candidate.getName().equals(methodName))
            .findFirst()
            .orElseThrow();
        return method.isAnnotationPresent(Primary.class);
    }
}
