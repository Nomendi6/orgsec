package com.nomendi6.orgsec.autoconfigure;

import com.nomendi6.orgsec.exceptions.OrgsecConfigurationException;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.util.ClassUtils;

/**
 * Checks that the {@code orgsec.storage.*} switches agree with each other, and with what is
 * actually on the classpath, before any bean is defined.
 *
 * <p>This runs as an {@link EnvironmentPostProcessor} rather than as a bean on purpose. Backend
 * selection happens through {@code @ConditionalOnProperty} evaluated during context startup, so a
 * contradictory pair of flags produces two competing {@code @Primary} storages and the context
 * dies on {@code NoUniqueBeanDefinitionException} while pre-instantiating singletons - before any
 * validator bean could be created, let alone print anything useful. Reading the raw
 * {@code Environment} up front is the only place a legible message survives.
 *
 * <p>Invalid configurations fail with stable, machine-searchable diagnostic codes. In
 * particular, the two historic Redis switches must always agree; warning and continuing can
 * leave a different storage serving authorization than the operator selected.
 */
public class OrgsecStorageActivationValidator implements EnvironmentPostProcessor {

    public static final String REDIS_ENABLED = "orgsec.storage.redis.enabled";
    public static final String FEATURES_REDIS_ENABLED = "orgsec.storage.features.redis-enabled";
    public static final String FEATURES_JWT_ENABLED = "orgsec.storage.features.jwt-enabled";

    /** @deprecated activation validation is now always strict. */
    @Deprecated
    public static final String STRICT_ACTIVATION = "orgsec.storage.strict-activation";

    public static final String REDIS_ACTIVATION_MISMATCH = "ORGSEC_STORAGE_REDIS_ACTIVATION_MISMATCH";
    public static final String JWT_MODULE_REQUIRED = "ORGSEC_STORAGE_JWT_MODULE_REQUIRED";
    public static final String REDIS_MODULE_REQUIRED = "ORGSEC_STORAGE_REDIS_MODULE_REQUIRED";
    public static final String JWT_REDIS_UNSUPPORTED = "ORGSEC_STORAGE_JWT_REDIS_UNSUPPORTED";

    private static final String JWT_STORAGE_CLASS = "com.nomendi6.orgsec.storage.jwt.JwtSecurityDataStorage";
    private static final String REDIS_STORAGE_CLASS = "com.nomendi6.orgsec.storage.redis.RedisSecurityDataStorage";

    public OrgsecStorageActivationValidator(DeferredLogFactory ignored) {
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        boolean redisEnabled = flag(environment, REDIS_ENABLED);
        boolean featuresRedisEnabled = flag(environment, FEATURES_REDIS_ENABLED);
        boolean jwtEnabled = flag(environment, FEATURES_JWT_ENABLED);

        if (redisEnabled != featuresRedisEnabled) {
            throw configurationFailure(
                REDIS_ACTIVATION_MISMATCH,
                REDIS_ENABLED + "=" + redisEnabled + " but " +
                    FEATURES_REDIS_ENABLED + "=" + featuresRedisEnabled +
                    ". Set both properties to the same value."
            );
        }

        ClassLoader classLoader = classLoader(application);
        if (jwtEnabled && !ClassUtils.isPresent(JWT_STORAGE_CLASS, classLoader)) {
            throw configurationFailure(
                JWT_MODULE_REQUIRED,
                FEATURES_JWT_ENABLED + "=true requires com.nomendi6.orgsec:orgsec-storage-jwt"
            );
        }
        if (redisEnabled && !ClassUtils.isPresent(REDIS_STORAGE_CLASS, classLoader)) {
            throw configurationFailure(
                REDIS_MODULE_REQUIRED,
                REDIS_ENABLED + "=true requires com.nomendi6.orgsec:orgsec-storage-redis"
            );
        }
        if (jwtEnabled && redisEnabled) {
            throw configurationFailure(
                JWT_REDIS_UNSUPPORTED,
                "JWT and Redis storage cannot be enabled together without a separately frozen " +
                    "hybrid delegate contract. Enable exactly one generated-app storage profile."
            );
        }
    }

    /**
     * Reads a boolean the same way {@code @ConditionalOnProperty} does: relaxed binding for the
     * property name, case-insensitive for the value, absent means {@code false}.
     */
    private boolean flag(ConfigurableEnvironment environment, String name) {
        String value = environment.getProperty(name);
        return value != null && Boolean.parseBoolean(value.trim());
    }

    private ClassLoader classLoader(SpringApplication application) {
        ClassLoader classLoader = (application != null) ? application.getClassLoader() : null;
        return (classLoader != null) ? classLoader : ClassUtils.getDefaultClassLoader();
    }

    private OrgsecConfigurationException configurationFailure(String code, String detail) {
        return new OrgsecConfigurationException(code + ": " + detail);
    }
}
