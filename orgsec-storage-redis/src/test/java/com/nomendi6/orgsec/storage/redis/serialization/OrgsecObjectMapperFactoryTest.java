package com.nomendi6.orgsec.storage.redis.serialization;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.nomendi6.orgsec.model.PersonDef;
import com.nomendi6.orgsec.storage.redis.config.RedisStorageProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OrgsecObjectMapperFactoryTest {

    @Test
    void shouldUseDefaultConfigWhenConstructedWithoutConfig() {
        OrgsecObjectMapperFactory factory = new OrgsecObjectMapperFactory();

        assertThat(factory.getConfig()).isNotNull();
        assertThat(factory.getConfig().isStrictMode()).isFalse();
    }

    @Test
    void shouldUseDefaultConfigWhenConstructedWithNullConfig() {
        OrgsecObjectMapperFactory factory = new OrgsecObjectMapperFactory(null);

        assertThat(factory.getConfig()).isNotNull();
        assertThat(factory.getConfig().isFailOnUnknownProperties()).isFalse();
    }

    @Test
    void shouldCacheDomainObjectMapperInStandardMode() throws Exception {
        RedisStorageProperties.SerializationConfig config = new RedisStorageProperties.SerializationConfig();
        OrgsecObjectMapperFactory factory = new OrgsecObjectMapperFactory(config);

        ObjectMapper mapper = factory.getDomainObjectMapper();

        assertThat(factory.getDomainObjectMapper()).isSameAs(mapper);
        assertThat(mapper.isEnabled(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)).isFalse();
        assertThat(mapper.isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)).isFalse();
        assertThat(mapper.writeValueAsString(new PersonDef(1L, "Test"))).contains("personId", "Test");
    }

    @Test
    void shouldCreateStrictDomainObjectMapperWhenStrictModeIsEnabled() {
        RedisStorageProperties.SerializationConfig config = new RedisStorageProperties.SerializationConfig();
        config.setStrictMode(true);
        OrgsecObjectMapperFactory factory = new OrgsecObjectMapperFactory(config);

        ObjectMapper mapper = factory.getDomainObjectMapper();

        assertThat(mapper.isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)).isTrue();
        assertThat(mapper.isEnabled(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)).isTrue();
        assertThat(mapper.isEnabled(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)).isTrue();
        assertThat(mapper.isEnabled(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)).isTrue();
    }

    @Test
    void shouldCacheEventObjectMapper() {
        OrgsecObjectMapperFactory factory = new OrgsecObjectMapperFactory();

        ObjectMapper mapper = factory.getEventObjectMapper();

        assertThat(factory.getEventObjectMapper()).isSameAs(mapper);
        assertThat(mapper.isEnabled(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)).isFalse();
    }

    @Test
    void shouldCreateMapperVariantsAndExposeConfig() throws Exception {
        RedisStorageProperties.SerializationConfig config = new RedisStorageProperties.SerializationConfig();
        config.setFailOnUnknownProperties(true);
        OrgsecObjectMapperFactory factory = new OrgsecObjectMapperFactory(config);

        assertThat(factory.createObjectMapper().isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)).isTrue();
        assertThat(factory.createSecureObjectMapper().isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)).isTrue();
        assertThat(factory.createSimpleObjectMapper().writeValueAsString(java.time.LocalDate.of(2026, 4, 26)))
                .contains("2026");
        assertThat(factory.getConfig()).isSameAs(config);
    }
}
