package com.nomendi6.orgsec.storage.redis.preload;

import com.nomendi6.orgsec.model.OrganizationDef;
import com.nomendi6.orgsec.model.PersonDef;
import com.nomendi6.orgsec.model.RoleDef;
import com.nomendi6.orgsec.storage.redis.config.RedisStorageProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CacheWarmerTest {

    private RedisStorageProperties.PreloadConfig preloadConfig;
    private CacheWarmer cacheWarmer;

    @BeforeEach
    void setUp() {
        preloadConfig = new RedisStorageProperties.PreloadConfig();
        preloadConfig.setEnabled(true);
        preloadConfig.setStrategy("all");
        preloadConfig.setMode("eager");
        cacheWarmer = new CacheWarmer(preloadConfig);
    }

    @Nested
    class StrategySelectionTests {

        @Test
        void shouldUseEagerStrategyByDefault() {
            assertThat(cacheWarmer.getWarmingStrategy().getName()).isEqualTo("eager");
        }

        @Test
        void shouldUseProgressiveStrategy() {
            preloadConfig.setMode("progressive");
            CacheWarmer warmer = new CacheWarmer(preloadConfig);

            assertThat(warmer.getWarmingStrategy().getName()).isEqualTo("progressive");
        }

        @Test
        void shouldUseLazyStrategy() {
            preloadConfig.setMode("lazy");
            CacheWarmer warmer = new CacheWarmer(preloadConfig);

            assertThat(warmer.getWarmingStrategy().getName()).isEqualTo("lazy");
        }

        @Test
        void shouldDefaultToEagerForUnknownMode() {
            preloadConfig.setMode("unknown");
            CacheWarmer warmer = new CacheWarmer(preloadConfig);

            assertThat(warmer.getWarmingStrategy().getName()).isEqualTo("eager");
        }
    }

    @Nested
    class WarmupDisabledTests {

        @Test
        void shouldReturnZeroCountsWhenDisabled() {
            preloadConfig.setEnabled(false);
            CacheWarmer warmer = new CacheWarmer(preloadConfig);

            WarmupStats stats = warmer.warmup();

            assertThat(stats.getPersonCount()).isZero();
            assertThat(stats.getOrganizationCount()).isZero();
            assertThat(stats.getRoleCount()).isZero();
        }
    }

    @Nested
    class PersonWarmupTests {

        @Test
        void shouldReturnZeroWhenLoaderNotConfigured() {
            cacheWarmer.setPersonBatchStore(persons -> {});

            int count = cacheWarmer.warmupPersons();

            assertThat(count).isZero();
        }

        @Test
        void shouldReturnZeroWhenStoreNotConfigured() {
            cacheWarmer.setPersonLoader(() -> Map.of(1L, new PersonDef(1L, "Test")));

            int count = cacheWarmer.warmupPersons();

            assertThat(count).isZero();
        }

        @Test
        void shouldWarmupPersons() {
            Map<Long, PersonDef> persons = Map.of(
                    1L, new PersonDef(1L, "Person 1"),
                    2L, new PersonDef(2L, "Person 2")
            );

            AtomicInteger storedCount = new AtomicInteger(0);

            cacheWarmer.setPersonLoader(() -> persons);
            cacheWarmer.setPersonBatchStore(data -> storedCount.set(data.size()));

            int count = cacheWarmer.warmupPersons();

            assertThat(count).isEqualTo(2);
            assertThat(storedCount.get()).isEqualTo(2);
        }

        @Test
        void shouldReturnZeroForEmptyData() {
            cacheWarmer.setPersonLoader(Map::of);
            cacheWarmer.setPersonBatchStore(data -> {});

            int count = cacheWarmer.warmupPersons();

            assertThat(count).isZero();
        }
    }

    @Nested
    class OrganizationWarmupTests {

        @Test
        void shouldWarmupOrganizations() {
            Map<Long, OrganizationDef> orgs = new HashMap<>();
            OrganizationDef org1 = new OrganizationDef();
            org1.organizationId = 1L;
            orgs.put(1L, org1);

            AtomicInteger storedCount = new AtomicInteger(0);

            cacheWarmer.setOrganizationLoader(() -> orgs);
            cacheWarmer.setOrganizationBatchStore(data -> storedCount.set(data.size()));

            int count = cacheWarmer.warmupOrganizations();

            assertThat(count).isEqualTo(1);
            assertThat(storedCount.get()).isEqualTo(1);
        }
    }

    @Nested
    class RoleWarmupTests {

        @Test
        void shouldWarmupRoles() {
            Map<Long, RoleDef> roles = new HashMap<>();
            RoleDef role1 = new RoleDef();
            role1.roleId = 1L;
            roles.put(1L, role1);

            AtomicInteger storedCount = new AtomicInteger(0);

            cacheWarmer.setRoleLoader(() -> roles);
            cacheWarmer.setRoleBatchStore(data -> storedCount.set(data.size()));

            int count = cacheWarmer.warmupRoles();

            assertThat(count).isEqualTo(1);
            assertThat(storedCount.get()).isEqualTo(1);
        }
    }

    @Nested
    class FullWarmupTests {

        @Test
        void shouldWarmupAllStrategies() {
            preloadConfig.setStrategy("all");
            CacheWarmer warmer = new CacheWarmer(preloadConfig);

            setupLoaders(warmer);

            WarmupStats stats = warmer.warmup();

            assertThat(stats.getPersonCount()).isEqualTo(2);
            assertThat(stats.getOrganizationCount()).isEqualTo(1);
            assertThat(stats.getRoleCount()).isEqualTo(1);
        }

        @Test
        void shouldWarmupOnlyPersons() {
            preloadConfig.setStrategy("persons");
            CacheWarmer warmer = new CacheWarmer(preloadConfig);

            setupLoaders(warmer);

            WarmupStats stats = warmer.warmup();

            assertThat(stats.getPersonCount()).isEqualTo(2);
            assertThat(stats.getOrganizationCount()).isZero();
            assertThat(stats.getRoleCount()).isZero();
        }

        @Test
        void shouldWarmupOnlyOrganizations() {
            preloadConfig.setStrategy("organizations");
            CacheWarmer warmer = new CacheWarmer(preloadConfig);

            setupLoaders(warmer);

            WarmupStats stats = warmer.warmup();

            assertThat(stats.getPersonCount()).isZero();
            assertThat(stats.getOrganizationCount()).isEqualTo(1);
            assertThat(stats.getRoleCount()).isZero();
        }

        @Test
        void shouldWarmupOnlyRoles() {
            preloadConfig.setStrategy("roles");
            CacheWarmer warmer = new CacheWarmer(preloadConfig);

            setupLoaders(warmer);

            WarmupStats stats = warmer.warmup();

            assertThat(stats.getPersonCount()).isZero();
            assertThat(stats.getOrganizationCount()).isZero();
            assertThat(stats.getRoleCount()).isEqualTo(1);
        }

        @Test
        void shouldDefaultToAllForUnknownStrategy() {
            preloadConfig.setStrategy("unknown");
            CacheWarmer warmer = new CacheWarmer(preloadConfig);

            setupLoaders(warmer);

            WarmupStats stats = warmer.warmup();

            assertThat(stats.getPersonCount()).isEqualTo(2);
            assertThat(stats.getOrganizationCount()).isEqualTo(1);
            assertThat(stats.getRoleCount()).isEqualTo(1);
        }

        private void setupLoaders(CacheWarmer warmer) {
            // Persons
            Map<Long, PersonDef> persons = Map.of(
                    1L, new PersonDef(1L, "Person 1"),
                    2L, new PersonDef(2L, "Person 2")
            );
            warmer.setPersonLoader(() -> persons);
            warmer.setPersonBatchStore(data -> {});

            // Organizations
            Map<Long, OrganizationDef> orgs = new HashMap<>();
            OrganizationDef org1 = new OrganizationDef();
            org1.organizationId = 1L;
            orgs.put(1L, org1);
            warmer.setOrganizationLoader(() -> orgs);
            warmer.setOrganizationBatchStore(data -> {});

            // Roles
            Map<Long, RoleDef> roles = new HashMap<>();
            RoleDef role1 = new RoleDef();
            role1.roleId = 1L;
            roles.put(1L, role1);
            warmer.setRoleLoader(() -> roles);
            warmer.setRoleBatchStore(data -> {});
        }
    }

    @Nested
    class WarmupStatsTests {

        @Test
        void shouldReturnDurationInStats() {
            setupBasicLoaders(cacheWarmer);

            WarmupStats stats = cacheWarmer.warmup();

            assertThat(stats.getDurationMs()).isGreaterThanOrEqualTo(0);
            assertThat(stats.getTimestamp()).isNotNull();
        }

        @Test
        void shouldHandleLoaderException() {
            // Eager strategy catches exceptions and returns 0
            cacheWarmer.setPersonLoader(() -> {
                throw new RuntimeException("Database error");
            });
            cacheWarmer.setPersonBatchStore(data -> {});
            cacheWarmer.setOrganizationLoader(Map::of);
            cacheWarmer.setOrganizationBatchStore(data -> {});
            cacheWarmer.setRoleLoader(Map::of);
            cacheWarmer.setRoleBatchStore(data -> {});

            WarmupStats stats = cacheWarmer.warmup();

            // Person warmup fails but others succeed
            assertThat(stats.getPersonCount()).isZero();
            assertThat(stats.getOrganizationCount()).isZero();
            assertThat(stats.getRoleCount()).isZero();
        }

        private void setupBasicLoaders(CacheWarmer warmer) {
            warmer.setPersonLoader(Map::of);
            warmer.setPersonBatchStore(data -> {});
            warmer.setOrganizationLoader(Map::of);
            warmer.setOrganizationBatchStore(data -> {});
            warmer.setRoleLoader(Map::of);
            warmer.setRoleBatchStore(data -> {});
        }
    }

    @Nested
    class ConfigAccessTests {

        @Test
        void shouldReturnPreloadConfig() {
            assertThat(cacheWarmer.getPreloadConfig()).isEqualTo(preloadConfig);
        }
    }
}
