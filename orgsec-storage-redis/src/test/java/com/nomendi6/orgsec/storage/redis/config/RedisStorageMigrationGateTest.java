package com.nomendi6.orgsec.storage.redis.config;

import com.nomendi6.orgsec.exceptions.OrgsecConfigurationException;
import com.nomendi6.orgsec.fence.SecurityDatasetFenceStore;
import com.nomendi6.orgsec.storage.redis.RedisSecurityDataStorage;
import com.nomendi6.orgsec.storage.redis.bootstrap.RedisSnapshotLoader;
import com.nomendi6.orgsec.storage.redis.health.RedisStorageHealthIndicator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.LazyInitializationBeanFactoryPostProcessor;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RedisStorageMigrationGateTest {

    private static final String MANAGED_STORAGE_CONFIGURATION =
        "com.nomendi6.orgsec.storage.redis.RedisManagedStorageConfiguration";

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(RedisStorageAutoConfiguration.class))
        .withBean(RedisConnectionFactory.class, RedisStorageMigrationGateTest::pongConnectionFactory)
        .withPropertyValues(
            "orgsec.storage.redis.enabled=true",
            "orgsec.storage.redis.security-dataset-id=orgsec-test",
            "orgsec.storage.redis.preload.enabled=false"
        );

    @Test
    void reportsEveryMissingProtocolBeanInOneActionableFailure() {
        contextRunner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                .isInstanceOf(OrgsecConfigurationException.class)
                .hasMessageStartingWith(
                    RedisStorageAutoConfiguration.R110_MIGRATION_REQUIRED + ":"
                )
                .hasMessageContaining("Missing: SecurityDatasetFenceStore")
                .hasMessageContaining("RedisSnapshotLoader")
                .hasMessageContaining("Ambiguous: none")
                .hasMessageContaining("dependency-only upgrade from OrgSec <= 1.0.5")
                .hasMessageContaining("source-database fence migration")
                .hasMessageContaining("generated SecurityDatasetFenceStore adapter")
                .hasMessageContaining("generated RedisSnapshotLoader adapter")
                .hasMessageNotContaining("signed external");
        });
    }

    @Test
    void reportsEveryAmbiguousProtocolBeanAndItsBeanNamesInOneFailure() {
        contextRunner
            .withBean(
                "datasetFenceOne",
                SecurityDatasetFenceStore.class,
                () -> mock(SecurityDatasetFenceStore.class)
            )
            .withBean(
                "datasetFenceTwo",
                SecurityDatasetFenceStore.class,
                () -> mock(SecurityDatasetFenceStore.class)
            )
            .withBean(
                "snapshotLoaderOne",
                RedisSnapshotLoader.class,
                () -> mock(RedisSnapshotLoader.class)
            )
            .withBean(
                "snapshotLoaderTwo",
                RedisSnapshotLoader.class,
                () -> mock(RedisSnapshotLoader.class)
            )
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .isInstanceOf(OrgsecConfigurationException.class)
                    .hasMessageStartingWith(
                        RedisStorageAutoConfiguration.R110_MIGRATION_REQUIRED + ":"
                    )
                    .hasMessageContaining("Missing: none")
                    .hasMessageContaining(
                        "SecurityDatasetFenceStore=[datasetFenceOne, datasetFenceTwo]"
                    )
                    .hasMessageContaining(
                        "RedisSnapshotLoader=[snapshotLoaderOne, snapshotLoaderTwo]"
                    );
            });
    }

    @Test
    void acceptsExactlyOneBeanOfEachProtocolType() {
        withCompleteProtocol(contextRunner).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(SecurityDatasetFenceStore.class);
            assertThat(context).hasSingleBean(RedisSnapshotLoader.class);
            assertThat(context).hasBean("redisSecurityDataStorage");
            assertThat(context.getBean(RedisSecurityDataStorage.class).isReady()).isFalse();
            assertThat(context.getBean(RedisStorageHealthIndicator.class).health().getStatus())
                .isEqualTo(Status.OUT_OF_SERVICE);
            verifyNoInteractions(
                context.getBean(SecurityDatasetFenceStore.class),
                context.getBean(RedisSnapshotLoader.class)
            );
        });
    }

    @Test
    void stillRejectsMissingProtocolBeansWhenApplicationBeansAreLazy() {
        contextRunner
            .withPropertyValues("spring.main.lazy-initialization=true")
            .withInitializer(context -> context.addBeanFactoryPostProcessor(
                new LazyInitializationBeanFactoryPostProcessor()
            ))
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .isInstanceOf(OrgsecConfigurationException.class)
                    .hasMessageStartingWith(
                        RedisStorageAutoConfiguration.R110_MIGRATION_REQUIRED + ":"
                    )
                    .hasMessageContaining("SecurityDatasetFenceStore")
                    .hasMessageContaining("RedisSnapshotLoader")
                    .hasMessageNotContaining("signed external");
            });
    }

    @Test
    void rejectsMissingDatasetIdEvenWhenApplicationBeansAreLazy() {
        ApplicationContextRunner lazyRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RedisStorageAutoConfiguration.class))
            .withBean(
                RedisConnectionFactory.class,
                RedisStorageMigrationGateTest::pongConnectionFactory
            )
            .withPropertyValues(
                "orgsec.storage.redis.enabled=true",
                "orgsec.storage.redis.preload.enabled=false",
                "spring.main.lazy-initialization=true"
            )
            .withInitializer(context -> context.addBeanFactoryPostProcessor(
                new LazyInitializationBeanFactoryPostProcessor()
            ));

        withCompleteProtocol(lazyRunner).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                .isInstanceOf(BeanCreationException.class)
                .hasRootCauseInstanceOf(OrgsecConfigurationException.class)
                .hasStackTraceContaining(RedisStorageProperties.INVALID_PROPERTY + ":")
                .hasStackTraceContaining("orgsec.storage.redis.security-dataset-id");
        });
    }

    @Test
    void doesNotRequireProtocolBeansWhenRedisStorageIsDisabled() {
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RedisStorageAutoConfiguration.class))
            .withPropertyValues("orgsec.storage.redis.enabled=false")
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).doesNotHaveBean("orgsecRedisR110MigrationGate");
            });
    }

    @Test
    void managedStorageFactoryIsNotAComponentScanCandidate() {
        ClassPathScanningCandidateComponentProvider scanner =
            new ClassPathScanningCandidateComponentProvider(true);

        assertThat(scanner.findCandidateComponents("com.nomendi6.orgsec.storage.redis"))
            .extracting(definition -> definition.getBeanClassName())
            .doesNotContain(MANAGED_STORAGE_CONFIGURATION);
    }

    @Test
    void discoversMigrationGateThroughBootAutoConfigurationMetadata() {
        SpringApplication application = new SpringApplication(DiscoveryApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setLogStartupInfo(false);

        assertThatThrownBy(() -> application.run(
            "--spring.main.banner-mode=off",
            "--spring.main.lazy-initialization=true",
            "--orgsec.storage.redis.enabled=true",
            "--orgsec.storage.redis.security-dataset-id=orgsec-test"
        ))
            .hasStackTraceContaining(RedisStorageAutoConfiguration.R110_MIGRATION_REQUIRED)
            .hasStackTraceContaining("RedisSnapshotLoader");
    }

    private static ApplicationContextRunner withCompleteProtocol(
            ApplicationContextRunner runner) {
        return runner
            .withBean(
                SecurityDatasetFenceStore.class,
                () -> mock(SecurityDatasetFenceStore.class)
            )
            .withBean(
                RedisSnapshotLoader.class,
                () -> mock(RedisSnapshotLoader.class)
            );
    }

    private static RedisConnectionFactory pongConnectionFactory() {
        RedisConnection connection = mock(RedisConnection.class);
        when(connection.ping()).thenReturn("PONG");
        RedisConnectionFactory connectionFactory = mock(RedisConnectionFactory.class);
        when(connectionFactory.getConnection()).thenReturn(connection);
        return connectionFactory;
    }

    @SpringBootConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class DiscoveryApplication {
    }
}
