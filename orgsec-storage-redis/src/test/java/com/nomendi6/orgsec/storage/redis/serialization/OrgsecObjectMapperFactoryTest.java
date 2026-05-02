package com.nomendi6.orgsec.storage.redis.serialization;

import com.nomendi6.orgsec.constants.PrivilegeDirection;
import com.nomendi6.orgsec.model.PersonDef;
import com.nomendi6.orgsec.model.PrivilegeDef;
import com.nomendi6.orgsec.storage.redis.config.RedisStorageProperties;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.EnumFeature;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        assertThat(mapper.isEnabled(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)).isTrue();
        assertThat(mapper.isEnabled(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS)).isTrue();
    }

    /**
     * Strict mode must reject numeric enum values rather than coercing them by ordinal.
     * Ordinal coercion is a privilege-escalation vector here: {@code PrivilegeDirection}
     * ordinal 4 is {@code ALL}, so a numeric value in cached JSON would silently widen access.
     */
    @Test
    void shouldRejectNumericEnumValuesInStrictMode() {
        RedisStorageProperties.SerializationConfig config = new RedisStorageProperties.SerializationConfig();
        config.setStrictMode(true);
        OrgsecObjectMapperFactory factory = new OrgsecObjectMapperFactory(config);

        ObjectMapper mapper = factory.getDomainObjectMapper();

        assertThatThrownBy(() -> mapper.readValue("{\"company\":4}", PrivilegeDef.class))
            .isInstanceOf(DatabindException.class);
    }

    @Test
    void shouldCoerceNumericEnumValuesByOrdinalWhenNotInStrictMode() {
        OrgsecObjectMapperFactory factory = new OrgsecObjectMapperFactory();

        PrivilegeDef parsed = factory.getDomainObjectMapper().readValue("{\"company\":4}", PrivilegeDef.class);

        assertThat(parsed.company).isEqualTo(PrivilegeDirection.ALL);
    }

    @Test
    void shouldCacheEventObjectMapper() {
        OrgsecObjectMapperFactory factory = new OrgsecObjectMapperFactory();

        ObjectMapper mapper = factory.getEventObjectMapper();

        assertThat(factory.getEventObjectMapper()).isSameAs(mapper);
        assertThat(mapper.writeValueAsString(java.time.LocalDate.of(2026, 4, 26))).contains("2026");
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
