package com.nomendi6.orgsec.storage.redis.config;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.TimeoutOptions;
import io.lettuce.core.api.StatefulConnection;
import io.lettuce.core.resource.ClientResources;
import io.lettuce.core.resource.DefaultClientResources;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettucePoolingClientConfiguration;

import java.time.Duration;

/**
 * Configuration for Lettuce connection pooling.
 * <p>
 * Provides optimized connection pool settings for high-performance Redis operations.
 * This configuration is applied when connection pooling is enabled.
 * </p>
 */
@Configuration
@ConditionalOnClass(LettuceConnectionFactory.class)
@ConditionalOnProperty(prefix = "orgsec.storage.redis", name = "enabled", havingValue = "true")
public class LettucePoolConfiguration {

    private static final Logger log = LoggerFactory.getLogger(LettucePoolConfiguration.class);

    /**
     * Configures Lettuce client resources with optimized settings.
     * <p>
     * Uses default thread pools with computation and I/O thread sizes
     * optimized for the number of available processors.
     * </p>
     */
    @Bean(destroyMethod = "shutdown")
    @ConditionalOnMissingBean
    public ClientResources lettuceClientResources() {
        log.info("Creating optimized Lettuce ClientResources");
        return DefaultClientResources.builder()
                .ioThreadPoolSize(Runtime.getRuntime().availableProcessors())
                .computationThreadPoolSize(Runtime.getRuntime().availableProcessors())
                .build();
    }

    /**
     * Configures the connection pool for Lettuce.
     *
     * @param properties the Redis storage properties
     * @return configured pool config
     */
    @Bean
    @ConditionalOnMissingBean
    public GenericObjectPoolConfig<StatefulConnection<?, ?>> lettucePoolConfig(RedisStorageProperties properties) {
        RedisStorageProperties.PoolConfig poolConfig = properties.getPool();

        GenericObjectPoolConfig<StatefulConnection<?, ?>> config = new GenericObjectPoolConfig<>();
        config.setMinIdle(poolConfig.getMinIdle());
        config.setMaxIdle(poolConfig.getMaxIdle());
        config.setMaxTotal(poolConfig.getMaxActive());
        config.setMaxWait(Duration.ofMillis(poolConfig.getMaxWait()));
        config.setTestWhileIdle(poolConfig.isTestWhileIdle());
        config.setTimeBetweenEvictionRuns(Duration.ofMillis(poolConfig.getTimeBetweenEvictionRuns()));
        config.setMinEvictableIdleTime(Duration.ofMillis(poolConfig.getMinEvictableIdleTime()));

        // Enable test on borrow for connection validation
        config.setTestOnBorrow(true);
        config.setTestOnReturn(false);

        // Block when exhausted (wait for connection)
        config.setBlockWhenExhausted(true);

        log.info("Configured Lettuce pool: minIdle={}, maxIdle={}, maxActive={}, maxWait={}ms",
                poolConfig.getMinIdle(), poolConfig.getMaxIdle(),
                poolConfig.getMaxActive(), poolConfig.getMaxWait());

        return config;
    }

    /**
     * Creates the Lettuce connection factory with pooling enabled.
     *
     * @param properties       the Redis storage properties
     * @param clientResources  the client resources
     * @param poolConfig       the pool configuration
     * @return configured connection factory
     */
    @Bean
    @ConditionalOnMissingBean(RedisConnectionFactory.class)
    public LettuceConnectionFactory redisConnectionFactory(
            RedisStorageProperties properties,
            ClientResources clientResources,
            GenericObjectPoolConfig<StatefulConnection<?, ?>> poolConfig) {

        // Configure Redis server connection
        RedisStandaloneConfiguration serverConfig = new RedisStandaloneConfiguration();
        serverConfig.setHostName(properties.getHost());
        serverConfig.setPort(properties.getPort());
        if (properties.getPassword() != null && !properties.getPassword().isEmpty()) {
            serverConfig.setPassword(properties.getPassword());
        }

        // Build client configuration
        LettuceClientConfiguration clientConfig;

        if (properties.getPool().isEnabled()) {
            // Pooling configuration
            LettucePoolingClientConfiguration.LettucePoolingClientConfigurationBuilder builder = LettucePoolingClientConfiguration.builder()
                    .poolConfig(poolConfig)
                    .clientResources(clientResources)
                    .commandTimeout(Duration.ofMillis(properties.getTimeout()))
                    .clientOptions(createClientOptions(properties));
            if (properties.isSsl()) {
                builder.useSsl();
            }
            clientConfig = builder.build();

            log.info("Creating LettuceConnectionFactory with connection pooling enabled");
        } else {
            // Non-pooling configuration
            LettuceClientConfiguration.LettuceClientConfigurationBuilder builder = LettuceClientConfiguration.builder()
                    .clientResources(clientResources)
                    .commandTimeout(Duration.ofMillis(properties.getTimeout()))
                    .clientOptions(createClientOptions(properties));
            if (properties.isSsl()) {
                builder.useSsl();
            }
            clientConfig = builder.build();

            log.info("Creating LettuceConnectionFactory without connection pooling");
        }

        LettuceConnectionFactory factory = new LettuceConnectionFactory(serverConfig, clientConfig);
        factory.setValidateConnection(true);

        log.info("Configured Redis connection: host={}, port={}, timeout={}ms, ssl={}",
                properties.getHost(), properties.getPort(), properties.getTimeout(), properties.isSsl());

        return factory;
    }

    /**
     * Creates client options with performance and reliability settings.
     */
    private ClientOptions createClientOptions(RedisStorageProperties properties) {
        return ClientOptions.builder()
                // Disconnect if connection becomes stale
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                // Auto-reconnect on connection failure
                .autoReconnect(true)
                // Cancel commands on reconnect to avoid stale results
                .cancelCommandsOnReconnectFailure(true)
                // Timeout options
                .timeoutOptions(TimeoutOptions.builder()
                        .fixedTimeout(Duration.ofMillis(properties.getTimeout()))
                        .build())
                .build();
    }
}
