package com.nomendi6.orgsec.storage.inmemory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nomendi6.orgsec.common.service.SecurityEventPublisher;
import com.nomendi6.orgsec.model.PersonDef;
import com.nomendi6.orgsec.provider.SecurityQueryProvider;
import com.nomendi6.orgsec.storage.inmemory.loader.OrganizationLoader;
import com.nomendi6.orgsec.storage.inmemory.loader.PersonLoader;
import com.nomendi6.orgsec.storage.inmemory.loader.PrivilegeLoader;
import com.nomendi6.orgsec.storage.inmemory.loader.RoleLoader;
import com.nomendi6.orgsec.storage.inmemory.store.AllOrganizationsStore;
import com.nomendi6.orgsec.storage.inmemory.store.AllPersonsStore;
import com.nomendi6.orgsec.storage.inmemory.store.AllPrivilegesStore;
import com.nomendi6.orgsec.storage.inmemory.store.AllRolesStore;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

/**
 * N1 GET/LIST proof on InMemory: a committed assignment is visible after notify; a rolled-back
 * one is not. The test mutates the query-provider source the way a database would, then lets
 * {@link SecurityEventPublisher} decide when {@code notify*} (and therefore {@code refresh}) runs.
 */
class SecurityEventPublisherVisibilityTest {

    private static final long PERSON_ID = 1L;

    private final AtomicReference<PersonDef> source = new AtomicReference<>(person("Alice"));
    private AllPersonsStore personsStore;
    private InMemorySecurityDataStorage storage;
    private SecurityEventPublisher publisher;
    private AbstractPlatformTransactionManager transactionManager;

    @BeforeEach
    void setUp() {
        personsStore = new AllPersonsStore();
        AllOrganizationsStore organizationsStore = new AllOrganizationsStore();
        AllRolesStore rolesStore = new AllRolesStore();
        AllPrivilegesStore privilegesStore = new AllPrivilegesStore();
        PersonLoader personLoader = mock(PersonLoader.class);
        doAnswer(invocation -> {
            PersonDef current = source.get();
            if (current != null) {
                personsStore.putPerson(current.personId, copy(current));
            }
            return null;
        }).when(personLoader).loadPersonsFromQueryResults(any(), any(), any(), any());

        SecurityQueryProvider queryProvider = mock(SecurityQueryProvider.class);
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

        storage = new InMemorySecurityDataStorage(
            personsStore,
            organizationsStore,
            rolesStore,
            privilegesStore,
            personLoader,
            mock(OrganizationLoader.class),
            mock(RoleLoader.class),
            mock(PrivilegeLoader.class),
            queryProvider
        );
        storage.initialize();
        publisher = new SecurityEventPublisher(storage);
        transactionManager = new ResourcelessTransactionManager();
    }

    @Test
    void committedAssignmentIsVisibleOnGetAndList() {
        assertThat(storage.getPerson(PERSON_ID).personName).isEqualTo("Alice");

        TransactionStatus status = transactionManager.getTransaction(TransactionDefinition.withDefaults());
        source.set(person("Alice promoted"));
        publisher.partyRoleChanged(99L);

        assertThat(storage.getPerson(PERSON_ID).personName)
            .as("uncommitted notify must not reload")
            .isEqualTo("Alice");

        transactionManager.commit(status);

        assertThat(storage.getPerson(PERSON_ID).personName).isEqualTo("Alice promoted");
        assertThat(storage.createSnapshot().getPersons())
            .containsOnlyKeys(PERSON_ID)
            .extractingByKey(PERSON_ID)
            .extracting(person -> person.personName)
            .isEqualTo("Alice promoted");
    }

    @Test
    void rolledBackAssignmentIsNotVisible() {
        TransactionStatus status = transactionManager.getTransaction(TransactionDefinition.withDefaults());
        PersonDef previous = source.get();
        source.set(person("should not leak"));
        publisher.partyRoleChanged(99L);
        source.set(previous);
        transactionManager.rollback(status);

        assertThat(storage.getPerson(PERSON_ID).personName).isEqualTo("Alice");
        assertThat(storage.createSnapshot().getPersons()).containsOnlyKeys(PERSON_ID);
    }

    @Test
    void noTransactionReloadsImmediately() {
        source.set(person("Alice now"));
        publisher.partyRoleChanged(99L);

        assertThat(storage.getPerson(PERSON_ID).personName).isEqualTo("Alice now");
        assertThat(storage.createSnapshot().getPersons()).containsOnlyKeys(PERSON_ID);
    }

    private static PersonDef person(String name) {
        return new PersonDef(PERSON_ID, name);
    }

    private static PersonDef copy(PersonDef source) {
        return new PersonDef(source.personId, source.personName);
    }

    private static final class ResourcelessTransactionManager extends AbstractPlatformTransactionManager {

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }
}
