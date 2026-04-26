package com.nomendi6.orgsec.storage.redis.config;

import io.lettuce.core.resource.ClientResources;
import io.lettuce.core.api.StatefulConnection;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import static org.assertj.core.api.Assertions.assertThat;

class LettucePoolConfigurationTest {

    private final LettucePoolConfiguration configuration = new LettucePoolConfiguration();

    @Test
    void shouldCreateClientResources() {
        ClientResources resources = configuration.lettuceClientResources();

        assertThat(resources).isNotNull();

        resources.shutdown();
    }

    @Test
    void shouldMapPoolProperties() {
        RedisStorageProperties properties = new RedisStorageProperties();
        properties.getPool().setMinIdle(2);
        properties.getPool().setMaxIdle(4);
        properties.getPool().setMaxActive(8);
        properties.getPool().setMaxWait(1234);
        properties.getPool().setTestWhileIdle(false);
        properties.getPool().setTimeBetweenEvictionRuns(5000);
        properties.getPool().setMinEvictableIdleTime(6000);

        GenericObjectPoolConfig<StatefulConnection<?, ?>> config = configuration.lettucePoolConfig(properties);

        assertThat(config.getMinIdle()).isEqualTo(2);
        assertThat(config.getMaxIdle()).isEqualTo(4);
        assertThat(config.getMaxTotal()).isEqualTo(8);
        assertThat(config.getMaxWaitDuration().toMillis()).isEqualTo(1234);
        assertThat(config.getTestWhileIdle()).isFalse();
        assertThat(config.getDurationBetweenEvictionRuns().toMillis()).isEqualTo(5000);
        assertThat(config.getMinEvictableIdleDuration().toMillis()).isEqualTo(6000);
        assertThat(config.getTestOnBorrow()).isTrue();
        assertThat(config.getTestOnReturn()).isFalse();
        assertThat(config.getBlockWhenExhausted()).isTrue();
    }

    @Test
    void shouldCreatePooledConnectionFactoryWithPassword() {
        RedisStorageProperties properties = new RedisStorageProperties();
        properties.setHost("redis.local");
        properties.setPort(6380);
        properties.setPassword("secret");
        properties.setTimeout(1500);
        properties.getPool().setEnabled(true);
        ClientResources resources = configuration.lettuceClientResources();

        LettuceConnectionFactory factory = configuration.redisConnectionFactory(
                properties, resources, configuration.lettucePoolConfig(properties));

        assertThat(factory).isNotNull();
        assertThat(factory.getHostName()).isEqualTo("redis.local");
        assertThat(factory.getPort()).isEqualTo(6380);
        assertThat(factory.getValidateConnection()).isTrue();

        factory.destroy();
        resources.shutdown();
    }

    @Test
    void shouldCreateNonPooledConnectionFactoryWithoutPassword() {
        RedisStorageProperties properties = new RedisStorageProperties();
        properties.setHost("localhost");
        properties.setPort(6379);
        properties.setPassword("");
        properties.getPool().setEnabled(false);
        ClientResources resources = configuration.lettuceClientResources();

        LettuceConnectionFactory factory = configuration.redisConnectionFactory(
                properties, resources, configuration.lettucePoolConfig(properties));

        assertThat(factory).isNotNull();
        assertThat(factory.getHostName()).isEqualTo("localhost");
        assertThat(factory.getPort()).isEqualTo(6379);
        assertThat(factory.getValidateConnection()).isTrue();

        factory.destroy();
        resources.shutdown();
    }
}
