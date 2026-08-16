package com.nomendi6.orgsec.storage.jwt.config;

import com.nomendi6.orgsec.exceptions.OrgsecConfigurationException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class JwtStorageAutoConfigurationTest {

    @Test
    void failsFastWithStableDiagnosticWhenDecoderIsMissing() {
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JwtStorageAutoConfiguration.class))
            .withPropertyValues("orgsec.storage.features.jwt-enabled=true")
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(OrgsecConfigurationException.class)
                    .rootCause()
                    .hasMessageStartingWith("ORGSEC_JWT_DECODER_REQUIRED:");
            });
    }
}
