package com.nomendi6.orgsec.autoconfigure;

import org.apache.commons.logging.Log;
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
 * <p>Two situations are handled:
 *
 * <ul>
 *   <li>{@code orgsec.storage.redis.enabled} disagreeing with
 *       {@code orgsec.storage.features.redis-enabled}. Only the first actually activates the Redis
 *       backend; the second decides whether the in-memory storage still claims {@code @Primary}.
 *       On the 1.0.x line this warns and boots, because applications generated against 1.0.4 emit
 *       exactly this combination. Set {@code orgsec.storage.strict-activation=true} to refuse
 *       instead; that is the 2.0.0 default.</li>
 *   <li>{@code orgsec.storage.features.jwt-enabled=true} without {@code orgsec-storage-jwt} on the
 *       classpath. Always fatal: the in-memory storage has already stood down from
 *       {@code @Primary}, so nothing would serve authorization at all.</li>
 * </ul>
 */
public class OrgsecStorageActivationValidator implements EnvironmentPostProcessor {

    static final String REDIS_ENABLED = "orgsec.storage.redis.enabled";
    static final String FEATURES_REDIS_ENABLED = "orgsec.storage.features.redis-enabled";
    static final String FEATURES_JWT_ENABLED = "orgsec.storage.features.jwt-enabled";
    static final String STRICT_ACTIVATION = "orgsec.storage.strict-activation";

    private static final String JWT_STORAGE_CLASS = "com.nomendi6.orgsec.storage.jwt.JwtSecurityDataStorage";

    private final Log log;

    public OrgsecStorageActivationValidator(DeferredLogFactory logFactory) {
        this.log = logFactory.getLog(OrgsecStorageActivationValidator.class);
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        boolean jwtEnabled = flag(environment, FEATURES_JWT_ENABLED);
        if (jwtEnabled && !ClassUtils.isPresent(JWT_STORAGE_CLASS, classLoader(application))) {
            throw new IllegalStateException(
                FEATURES_JWT_ENABLED + "=true, but " + JWT_STORAGE_CLASS + " is not on the classpath. " +
                "Add the com.nomendi6.orgsec:orgsec-storage-jwt dependency, or set " +
                FEATURES_JWT_ENABLED + "=false. Starting would leave the application with no primary " +
                "SecurityDataStorage at all, because the in-memory storage stands down whenever JWT is enabled."
            );
        }

        boolean redisEnabled = flag(environment, REDIS_ENABLED);
        boolean featuresRedisEnabled = flag(environment, FEATURES_REDIS_ENABLED);
        if (redisEnabled == featuresRedisEnabled) {
            return;
        }

        String message =
            "Contradictory OrgSec storage configuration: " +
            REDIS_ENABLED + "=" + redisEnabled + " but " + FEATURES_REDIS_ENABLED + "=" + featuresRedisEnabled + ". " +
            REDIS_ENABLED + " is the only switch that activates the Redis backend; " +
            FEATURES_REDIS_ENABLED + " only decides whether the in-memory storage keeps @Primary. " +
            "Set both to the same value. Effective behaviour: Redis backend " +
            (redisEnabled ? "active" : "inactive") + ", in-memory storage " +
            (featuresRedisEnabled ? "not primary" : "primary") + ".";

        if (flag(environment, STRICT_ACTIVATION)) {
            throw new IllegalStateException(
                message + " (Refusing to start because " + STRICT_ACTIVATION + "=true.)"
            );
        }
        log.warn(message + " Continuing because " + STRICT_ACTIVATION + " is false; this becomes fatal in 2.0.0.");
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
}
