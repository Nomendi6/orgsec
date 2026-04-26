package com.nomendi6.orgsec.storage.inmemory;

import com.nomendi6.orgsec.model.OrganizationDef;
import com.nomendi6.orgsec.model.PersonDef;
import com.nomendi6.orgsec.model.PrivilegeDef;
import com.nomendi6.orgsec.model.RoleDef;
import com.nomendi6.orgsec.provider.SecurityQueryProvider;
import com.nomendi6.orgsec.storage.inmemory.loader.OrganizationLoader;
import com.nomendi6.orgsec.storage.inmemory.loader.PersonLoader;
import com.nomendi6.orgsec.storage.inmemory.loader.PrivilegeLoader;
import com.nomendi6.orgsec.storage.inmemory.loader.RoleLoader;
import com.nomendi6.orgsec.storage.inmemory.store.AllOrganizationsStore;
import com.nomendi6.orgsec.storage.inmemory.store.AllPersonsStore;
import com.nomendi6.orgsec.storage.inmemory.store.AllPrivilegesStore;
import com.nomendi6.orgsec.storage.inmemory.store.AllRolesStore;
import jakarta.persistence.Tuple;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InMemorySecurityDataStorageTest {

    @Mock
    private SecurityQueryProvider queryProvider;
    @Mock
    private PersonLoader personLoader;
    @Mock
    private OrganizationLoader organizationLoader;
    @Mock
    private RoleLoader roleLoader;
    @Mock
    private PrivilegeLoader privilegeLoader;

    private AllPersonsStore personsStore;
    private AllOrganizationsStore organizationsStore;
    private AllRolesStore rolesStore;
    private AllPrivilegesStore privilegesStore;

    private InMemorySecurityDataStorage storage;

    @BeforeEach
    void setUp() {
        personsStore = new AllPersonsStore();
        organizationsStore = new AllOrganizationsStore();
        rolesStore = new AllRolesStore();
        privilegesStore = new AllPrivilegesStore();

        storage = new InMemorySecurityDataStorage(
                personsStore,
                organizationsStore,
                rolesStore,
                privilegesStore,
                personLoader,
                organizationLoader,
                roleLoader,
                privilegeLoader,
                queryProvider
        );
    }

    @Nested
    class InitializationTests {

        @BeforeEach
        void setupMocks() {
            when(queryProvider.loadAllPartyRoles()).thenReturn(new ArrayList<>());
            when(queryProvider.loadAllPartyRolePrivilegesAsStrings()).thenReturn(new ArrayList<>());
            when(queryProvider.loadAllPositionRoles()).thenReturn(new ArrayList<>());
            when(queryProvider.loadAllPositionRolePrivilegesAsStrings()).thenReturn(new ArrayList<>());
            when(queryProvider.loadAllParties()).thenReturn(new ArrayList<>());
            when(queryProvider.loadAllPartyAssignedRoles()).thenReturn(new ArrayList<>());
            when(queryProvider.loadAllPersons()).thenReturn(new ArrayList<>());
            when(queryProvider.loadAllPersonParties()).thenReturn(new ArrayList<>());
            when(queryProvider.loadAllPersonPartyRoles()).thenReturn(new ArrayList<>());
            when(queryProvider.loadAllPersonPositionRoles()).thenReturn(new ArrayList<>());
        }

        @Test
        void shouldNotBeReadyBeforeInitialization() {
            assertThat(storage.isReady()).isFalse();
        }

        @Test
        void shouldBeReadyAfterInitialization() {
            storage.initialize();
            assertThat(storage.isReady()).isTrue();
        }

        @Test
        void shouldLoadDataDuringInitialization() {
            storage.initialize();

            verify(queryProvider).loadAllPartyRoles();
            verify(queryProvider).loadAllPersons();
            verify(queryProvider).loadAllParties();
            verify(roleLoader).loadRolesFromQueryResults(any(), any(), any(), any());
            verify(organizationLoader).loadOrganizationsFromQueryResults(any(), any());
            verify(personLoader).loadPersonsFromQueryResults(any(), any(), any(), any());
        }

        @Test
        void shouldRefreshClearAndReload() {
            // Initialize first
            storage.initialize();
            personsStore.putPerson(1L, new PersonDef(1L, "Test"));

            // Then refresh
            storage.refresh();

            // Verify data was cleared and reloaded
            verify(roleLoader, times(2)).loadRolesFromQueryResults(any(), any(), any(), any());
        }

        @Test
        void shouldThrowExceptionOnInitializationFailure() {
            when(queryProvider.loadAllPartyRoles()).thenThrow(new RuntimeException("DB error"));

            assertThatThrownBy(() -> storage.initialize())
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("initialization failed");

            assertThat(storage.isReady()).isFalse();
        }
    }

    @Nested
    class GetOperationsTests {

        @BeforeEach
        void initializeStorage() {
            when(queryProvider.loadAllPartyRoles()).thenReturn(new ArrayList<>());
            when(queryProvider.loadAllPartyRolePrivilegesAsStrings()).thenReturn(new ArrayList<>());
            when(queryProvider.loadAllPositionRoles()).thenReturn(new ArrayList<>());
            when(queryProvider.loadAllPositionRolePrivilegesAsStrings()).thenReturn(new ArrayList<>());
            when(queryProvider.loadAllParties()).thenReturn(new ArrayList<>());
            when(queryProvider.loadAllPartyAssignedRoles()).thenReturn(new ArrayList<>());
            when(queryProvider.loadAllPersons()).thenReturn(new ArrayList<>());
            when(queryProvider.loadAllPersonParties()).thenReturn(new ArrayList<>());
            when(queryProvider.loadAllPersonPartyRoles()).thenReturn(new ArrayList<>());
            when(queryProvider.loadAllPersonPositionRoles()).thenReturn(new ArrayList<>());

            storage.initialize();
        }

        @Test
        void shouldGetPerson() {
            PersonDef person = new PersonDef(1L, "Test Person");
            personsStore.putPerson(1L, person);

            PersonDef result = storage.getPerson(1L);

            assertThat(result).isNotNull();
            assertThat(result.personName).isEqualTo("Test Person");
        }

        @Test
        void shouldReturnNullForNonExistentPerson() {
            assertThat(storage.getPerson(999L)).isNull();
        }

        @Test
        void shouldReturnNullWhenNotReady() {
            InMemorySecurityDataStorage uninitializedStorage = new InMemorySecurityDataStorage(
                    personsStore, organizationsStore, rolesStore, privilegesStore,
                    personLoader, organizationLoader, roleLoader, privilegeLoader, queryProvider
            );

            assertThat(uninitializedStorage.getPerson(1L)).isNull();
        }

        @Test
        void shouldGetOrganization() {
            OrganizationDef org = new OrganizationDef();
            org.organizationId = 1L;
            org.organizationName = "Test Org";
            organizationsStore.putOrganization(1L, org);

            OrganizationDef result = storage.getOrganization(1L);

            assertThat(result).isNotNull();
            assertThat(result.organizationName).isEqualTo("Test Org");
        }

        @Test
        void shouldGetPrivilege() {
            PrivilegeDef privilege = new PrivilegeDef("READ_USER", "user");
            privilegesStore.registerPrivilege("READ_USER", privilege);

            PrivilegeDef result = storage.getPrivilege("READ_USER");

            assertThat(result).isNotNull();
            assertThat(result.resourceName).isEqualTo("user");
        }

        @Test
        void shouldGetPartyRole() {
            RoleDef role = new RoleDef(1L, "Admin");
            rolesStore.putOrganizationRole(1L, role);

            RoleDef result = storage.getPartyRole(1L);

            assertThat(result).isNotNull();
            assertThat(result.name).isEqualTo("Admin");
        }
    }

    @Nested
    class UpdateOperationsTests {

        @BeforeEach
        void initializeStorage() {
            when(queryProvider.loadAllPartyRoles()).thenReturn(new ArrayList<>());
            when(queryProvider.loadAllPartyRolePrivilegesAsStrings()).thenReturn(new ArrayList<>());
            when(queryProvider.loadAllPositionRoles()).thenReturn(new ArrayList<>());
            when(queryProvider.loadAllPositionRolePrivilegesAsStrings()).thenReturn(new ArrayList<>());
            when(queryProvider.loadAllParties()).thenReturn(new ArrayList<>());
            when(queryProvider.loadAllPartyAssignedRoles()).thenReturn(new ArrayList<>());
            when(queryProvider.loadAllPersons()).thenReturn(new ArrayList<>());
            when(queryProvider.loadAllPersonParties()).thenReturn(new ArrayList<>());
            when(queryProvider.loadAllPersonPartyRoles()).thenReturn(new ArrayList<>());
            when(queryProvider.loadAllPersonPositionRoles()).thenReturn(new ArrayList<>());

            storage.initialize();
        }

        @Test
        void shouldUpdatePerson() {
            PersonDef person = new PersonDef(1L, "New Person");

            storage.updatePerson(1L, person);

            assertThat(personsStore.getPerson(1L)).isNotNull();
            assertThat(personsStore.getPerson(1L).personName).isEqualTo("New Person");
        }

        @Test
        void shouldUpdateOrganization() {
            OrganizationDef org = new OrganizationDef();
            org.organizationId = 1L;
            org.organizationName = "New Org";

            storage.updateOrganization(1L, org);

            assertThat(organizationsStore.getOrganization(1L)).isNotNull();
        }

        @Test
        void shouldNotUpdateWhenNotReady() {
            InMemorySecurityDataStorage uninitializedStorage = new InMemorySecurityDataStorage(
                    new AllPersonsStore(), organizationsStore, rolesStore, privilegesStore,
                    personLoader, organizationLoader, roleLoader, privilegeLoader, queryProvider
            );

            uninitializedStorage.updatePerson(1L, new PersonDef(1L, "Test"));

            // Should not throw, but also should not store
        }
    }

    @Nested
    class NotificationTests {

        @BeforeEach
        void initializeStorage() {
            when(queryProvider.loadAllPartyRoles()).thenReturn(new ArrayList<>());
            when(queryProvider.loadAllPartyRolePrivilegesAsStrings()).thenReturn(new ArrayList<>());
            when(queryProvider.loadAllPositionRoles()).thenReturn(new ArrayList<>());
            when(queryProvider.loadAllPositionRolePrivilegesAsStrings()).thenReturn(new ArrayList<>());
            when(queryProvider.loadAllParties()).thenReturn(new ArrayList<>());
            when(queryProvider.loadAllPartyAssignedRoles()).thenReturn(new ArrayList<>());
            when(queryProvider.loadAllPersons()).thenReturn(new ArrayList<>());
            when(queryProvider.loadAllPersonParties()).thenReturn(new ArrayList<>());
            when(queryProvider.loadAllPersonPartyRoles()).thenReturn(new ArrayList<>());
            when(queryProvider.loadAllPersonPositionRoles()).thenReturn(new ArrayList<>());

            storage.initialize();
        }

        @Test
        void shouldSyncPersonOnNotification() {
            when(queryProvider.loadPersonById(1L)).thenReturn(new ArrayList<>());
            when(queryProvider.loadPersonPartiesByPersonId(1L)).thenReturn(new ArrayList<>());
            when(queryProvider.loadPersonPartyRolesByPersonId(1L)).thenReturn(new ArrayList<>());
            when(queryProvider.loadPersonPositionRolesByPersonId(1L)).thenReturn(new ArrayList<>());

            storage.notifyPersonChanged(1L);

            verify(personLoader).syncPerson(eq(1L), any(), any(), any(), any());
        }

        @Test
        void shouldSyncOrganizationOnNotification() {
            when(queryProvider.loadPartyById(1L)).thenReturn(new ArrayList<>());
            when(queryProvider.loadPartyAssignedRolesByPartyId(1L)).thenReturn(new ArrayList<>());

            storage.notifyOrganizationChanged(1L);

            verify(organizationLoader).syncParty(eq(1L), any(), any());
        }

        @Test
        void shouldSyncPartyRoleOnNotification() {
            when(queryProvider.loadPartyRoleById(1L)).thenReturn(new ArrayList<>());
            when(queryProvider.loadPartyRolePrivilegesByRoleIdAsStrings(1L)).thenReturn(new ArrayList<>());

            storage.notifyPartyRoleChanged(1L);

            verify(roleLoader).syncPartyRole(eq(1L), any(), any());
        }
    }

    @Nested
    class ConcurrencyTests {

        @BeforeEach
        void initializeStorage() {
            when(queryProvider.loadAllPartyRoles()).thenReturn(new ArrayList<>());
            when(queryProvider.loadAllPartyRolePrivilegesAsStrings()).thenReturn(new ArrayList<>());
            when(queryProvider.loadAllPositionRoles()).thenReturn(new ArrayList<>());
            when(queryProvider.loadAllPositionRolePrivilegesAsStrings()).thenReturn(new ArrayList<>());
            when(queryProvider.loadAllParties()).thenReturn(new ArrayList<>());
            when(queryProvider.loadAllPartyAssignedRoles()).thenReturn(new ArrayList<>());
            when(queryProvider.loadAllPersons()).thenReturn(new ArrayList<>());
            when(queryProvider.loadAllPersonParties()).thenReturn(new ArrayList<>());
            when(queryProvider.loadAllPersonPartyRoles()).thenReturn(new ArrayList<>());
            when(queryProvider.loadAllPersonPositionRoles()).thenReturn(new ArrayList<>());

            storage.initialize();
        }

        @Test
        void shouldHandleConcurrentReads() throws Exception {
            // Setup test data
            for (int i = 1; i <= 100; i++) {
                PersonDef person = new PersonDef((long) i, "Person " + i);
                personsStore.putPerson((long) i, person);
            }

            int threadCount = 10;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch latch = new CountDownLatch(threadCount);
            List<Boolean> results = new ArrayList<>();

            for (int i = 0; i < threadCount; i++) {
                final int threadId = i;
                executor.submit(() -> {
                    try {
                        for (int j = 1; j <= 100; j++) {
                            PersonDef person = storage.getPerson((long) j);
                            if (person == null || !person.personName.equals("Person " + j)) {
                                synchronized (results) {
                                    results.add(false);
                                }
                                return;
                            }
                        }
                        synchronized (results) {
                            results.add(true);
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }

            latch.await(10, TimeUnit.SECONDS);
            executor.shutdown();

            assertThat(results).hasSize(threadCount);
            assertThat(results).allMatch(r -> r);
        }

        @Test
        void shouldHandleConcurrentReadsAndWrites() throws Exception {
            int threadCount = 10;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch latch = new CountDownLatch(threadCount);

            // Half readers, half writers
            for (int i = 0; i < threadCount; i++) {
                final int threadId = i;
                if (threadId % 2 == 0) {
                    // Writer
                    executor.submit(() -> {
                        try {
                            for (int j = 0; j < 50; j++) {
                                PersonDef person = new PersonDef((long) (threadId * 100 + j), "Person " + threadId + "-" + j);
                                storage.updatePerson((long) (threadId * 100 + j), person);
                            }
                        } finally {
                            latch.countDown();
                        }
                    });
                } else {
                    // Reader
                    executor.submit(() -> {
                        try {
                            for (int j = 0; j < 100; j++) {
                                storage.getPerson((long) j);
                            }
                        } finally {
                            latch.countDown();
                        }
                    });
                }
            }

            boolean completed = latch.await(10, TimeUnit.SECONDS);
            executor.shutdown();

            assertThat(completed).isTrue();
        }
    }

    @Nested
    class ProviderTypeTests {

        @Test
        void shouldReturnCorrectProviderType() {
            assertThat(storage.getProviderType()).isEqualTo("in-memory");
        }

        @Test
        void shouldReturnStorageStats() {
            when(queryProvider.loadAllPartyRoles()).thenReturn(new ArrayList<>());
            when(queryProvider.loadAllPartyRolePrivilegesAsStrings()).thenReturn(new ArrayList<>());
            when(queryProvider.loadAllPositionRoles()).thenReturn(new ArrayList<>());
            when(queryProvider.loadAllPositionRolePrivilegesAsStrings()).thenReturn(new ArrayList<>());
            when(queryProvider.loadAllParties()).thenReturn(new ArrayList<>());
            when(queryProvider.loadAllPartyAssignedRoles()).thenReturn(new ArrayList<>());
            when(queryProvider.loadAllPersons()).thenReturn(new ArrayList<>());
            when(queryProvider.loadAllPersonParties()).thenReturn(new ArrayList<>());
            when(queryProvider.loadAllPersonPartyRoles()).thenReturn(new ArrayList<>());
            when(queryProvider.loadAllPersonPositionRoles()).thenReturn(new ArrayList<>());

            storage.initialize();
            personsStore.putPerson(1L, new PersonDef(1L, "Test"));

            String stats = storage.getStorageStats();

            assertThat(stats).contains("persons=1");
        }

        @Test
        void shouldReturnNotReadyStatsBeforeInit() {
            assertThat(storage.getStorageStats()).isEqualTo("Storage not ready");
        }
    }
}
