package com.nomendi6.orgsec.autoconfigure;

import com.nomendi6.orgsec.exceptions.OrgsecConfigurationException;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.util.ClassUtils;

/**
 * Validates storage activation switches before Spring starts defining storage beans.
 *
 * <p>The two Redis switches historically controlled different halves of the bean graph. A
 * mismatch could therefore create two primary storages or silently leave the in-memory delegate
 * serving requests. Validation runs as an {@link EnvironmentPostProcessor} so the diagnostic is
 * deterministic and precedes either failure mode.
 */
public final class OrgsecStorageActivationValidator implements EnvironmentPostProcessor {

    public static final String REDIS_ENABLED = "orgsec.storage.redis.enabled";
    public static final String FEATURES_REDIS_ENABLED = "orgsec.storage.features.redis-enabled";
    public static final String FEATURES_JWT_ENABLED = "orgsec.storage.features.jwt-enabled";

    public static final String REDIS_ACTIVATION_MISMATCH = "ORGSEC_STORAGE_REDIS_ACTIVATION_MISMATCH";
    public static final String JWT_MODULE_REQUIRED = "ORGSEC_STORAGE_JWT_MODULE_REQUIRED";
    public static final String REDIS_MODULE_REQUIRED = "ORGSEC_STORAGE_REDIS_MODULE_REQUIRED";
    public static final String JWT_REDIS_UNSUPPORTED = "ORGSEC_STORAGE_JWT_REDIS_UNSUPPORTED";

    private static final String JWT_STORAGE_CLASS =
        "com.nomendi6.orgsec.storage.jwt.JwtSecurityDataStorage";
    private static final String REDIS_STORAGE_CLASS =
        "com.nomendi6.orgsec.storage.redis.RedisSecurityDataStorage";

    /**
     * The deferred log factory is accepted by Spring Boot while creating environment processors.
     * No logger is currently needed, but keeping this constructor makes processor construction
     * explicit and compatible with Boot's early-startup lifecycle.
     */
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

    private static boolean flag(ConfigurableEnvironment environment, String name) {
        String value = environment.getProperty(name);
        return value != null && Boolean.parseBoolean(value.trim());
    }

    private static ClassLoader classLoader(SpringApplication application) {
        ClassLoader classLoader = application != null ? application.getClassLoader() : null;
        return classLoader != null ? classLoader : ClassUtils.getDefaultClassLoader();
    }

    private static OrgsecConfigurationException configurationFailure(String code, String detail) {
        return new OrgsecConfigurationException(code + ": " + detail);
    }
}
