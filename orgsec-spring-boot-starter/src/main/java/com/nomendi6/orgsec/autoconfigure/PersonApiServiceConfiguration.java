package com.nomendi6.orgsec.autoconfigure;

import com.nomendi6.orgsec.api.service.PersonApiService;
import com.nomendi6.orgsec.provider.SecurityQueryProvider;
import com.nomendi6.orgsec.storage.SecurityDataStorage;
import com.nomendi6.orgsec.storage.inmemory.loader.PersonLoader;
import com.nomendi6.orgsec.storage.inmemory.store.AllPersonsStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Conditional configuration for PersonApiService.
 * Only creates the bean when PersonLoader is available (inmemory storage module is active).
 */
@Configuration
@ConditionalOnBean(PersonLoader.class)
public class PersonApiServiceConfiguration {

    @Bean
    public PersonApiService personApiService(SecurityDataStorage securityDataStorage,
                                             SecurityQueryProvider queryProvider,
                                             PersonLoader personLoader,
                                             AllPersonsStore personsStore) {
        return new PersonApiService(securityDataStorage, queryProvider, personLoader, personsStore);
    }
}
