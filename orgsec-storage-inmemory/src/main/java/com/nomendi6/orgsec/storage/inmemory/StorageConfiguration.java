package com.nomendi6.orgsec.storage.inmemory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import com.nomendi6.orgsec.storage.SecurityDataStorage;

/**
 * Selects the {@code @Primary} {@link SecurityDataStorage} when JWT and Redis are both off.
 * JWT and Redis modules register their own primary beans when activated.
 */
@Configuration
public class StorageConfiguration {

    private static final Logger log = LoggerFactory.getLogger(StorageConfiguration.class);

    /**
     * Provides InMemorySecurityDataStorage as @Primary ONLY when JWT and Redis are disabled.
     * This is the default storage when no other storage modules are active.
     */
    @Bean
    @Primary
    @Qualifier("primaryInMemoryStorage")
    @ConditionalOnMissingBean(name = "primaryInMemoryStorage")
    @ConditionalOnProperty(
        prefix = "orgsec.storage.features",
        name = {"jwt-enabled", "redis-enabled"},
        havingValue = "false",
        matchIfMissing = true
    )
    public SecurityDataStorage primaryInMemoryStorage(InMemorySecurityDataStorage inMemoryStorage) {
        log.info("Configuring InMemorySecurityDataStorage as PRIMARY storage (no JWT/Redis modules active)");
        return inMemoryStorage;
    }

    /**
     * Provides InMemorySecurityDataStorage as a named delegate. This bean is never {@code @Primary}.
     */
    @Bean
    @Qualifier("delegateSecurityDataStorage")
    @ConditionalOnMissingBean(name = "delegateSecurityDataStorage")
    public SecurityDataStorage delegateSecurityDataStorage(InMemorySecurityDataStorage inMemoryStorage) {
        log.info("Registering InMemorySecurityDataStorage as delegate storage");
        return inMemoryStorage;
    }

    /**
     * Public delegate used by the JWT storage.
     *
     * <p>The default is the in-memory delegate. Applications may replace this named bean.
     * JWT and Redis together are rejected by the activation validator before the context
     * is created.
     */
    @Bean("jwtDelegateStorage")
    @ConditionalOnMissingBean(name = "jwtDelegateStorage")
    @ConditionalOnProperty(name = "orgsec.storage.features.jwt-enabled", havingValue = "true")
    public SecurityDataStorage jwtDelegateStorage(
        @Qualifier("delegateSecurityDataStorage") SecurityDataStorage delegateSecurityDataStorage
    ) {
        log.info("Registering {} as the JWT delegate storage", delegateSecurityDataStorage.getProviderType());
        return delegateSecurityDataStorage;
    }
}
