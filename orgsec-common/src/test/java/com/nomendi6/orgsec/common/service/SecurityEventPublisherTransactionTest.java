package com.nomendi6.orgsec.common.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nomendi6.orgsec.storage.SecurityDataStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

/**
 * N1: producer notify/publish is commit-bound. Rollback is silent. No transaction runs
 * immediately. {@code apply*} stays the consumer path and is never deferred.
 */
class SecurityEventPublisherTransactionTest {

    private SecurityDataStorage storage;
    private SecurityEventPublisher publisher;
    private AbstractPlatformTransactionManager transactionManager;

    @BeforeEach
    void setUp() {
        storage = mock(SecurityDataStorage.class);
        when(storage.getProviderType()).thenReturn("test");
        publisher = new SecurityEventPublisher(storage);
        transactionManager = new ResourcelessTransactionManager();
    }

    @Test
    void noTransactionNotifiesImmediately() {
        publisher.partyRoleChanged(11L);
        publisher.positionRoleChanged(12L);
        publisher.partyChanged(13L);
        publisher.personChanged(14L);

        verify(storage).notifyPartyRoleChanged(11L);
        verify(storage).notifyPositionRoleChanged(12L);
        verify(storage).notifyOrganizationChanged(13L);
        verify(storage).notifyPersonChanged(14L);
    }

    @Test
    void commitNotifiesOnceAfterCommit() {
        TransactionStatus status = transactionManager.getTransaction(TransactionDefinition.withDefaults());

        publisher.partyRoleAdded(21L);
        publisher.partyRoleChanged(21L);
        publisher.partyRoleDeleted(21L);
        publisher.positionRoleAdded(22L);
        publisher.positionRoleChanged(22L);
        publisher.positionRoleDeleted(22L);
        publisher.partyAdded(23L);
        publisher.partyChanged(23L);
        publisher.partyDeleted(23L);
        publisher.personAdded(24L);
        publisher.personChanged(24L);
        publisher.personDeleted(24L);

        verify(storage, never()).notifyPartyRoleChanged(21L);
        verify(storage, never()).notifyPositionRoleChanged(22L);
        verify(storage, never()).notifyOrganizationChanged(23L);
        verify(storage, never()).notifyPersonChanged(24L);

        transactionManager.commit(status);

        verify(storage, times(3)).notifyPartyRoleChanged(21L);
        verify(storage, times(3)).notifyPositionRoleChanged(22L);
        verify(storage, times(3)).notifyOrganizationChanged(23L);
        verify(storage, times(3)).notifyPersonChanged(24L);
    }

    @Test
    void rollbackDoesNotNotify() {
        TransactionStatus status = transactionManager.getTransaction(TransactionDefinition.withDefaults());

        publisher.partyRoleChanged(31L);
        publisher.positionRoleChanged(32L);
        publisher.partyChanged(33L);
        publisher.personChanged(34L);

        transactionManager.rollback(status);

        verify(storage, never()).notifyPartyRoleChanged(31L);
        verify(storage, never()).notifyPositionRoleChanged(32L);
        verify(storage, never()).notifyOrganizationChanged(33L);
        verify(storage, never()).notifyPersonChanged(34L);
    }

    @Test
    void applyMethodsNotifyImmediatelyInsideATransaction() {
        TransactionStatus status = transactionManager.getTransaction(TransactionDefinition.withDefaults());

        publisher.applyPartyRoleChanged(41L);
        publisher.applyPositionRoleChanges(42L);
        publisher.applyPartyChanges(43L);

        verify(storage).notifyPartyRoleChanged(41L);
        verify(storage).notifyPositionRoleChanged(42L);
        verify(storage).notifyOrganizationChanged(43L);

        transactionManager.rollback(status);

        verify(storage).notifyPartyRoleChanged(41L);
        verify(storage).notifyPositionRoleChanged(42L);
        verify(storage).notifyOrganizationChanged(43L);
    }

    @Test
    void afterCommitFailureDoesNotHideThatTheSourceCommitted() {
        doThrow(new IllegalStateException("redis down")).when(storage).notifyPartyRoleChanged(51L);
        TransactionStatus status = transactionManager.getTransaction(TransactionDefinition.withDefaults());
        publisher.partyRoleChanged(51L);

        assertThatThrownBy(() -> transactionManager.commit(status))
            .isInstanceOf(SecurityNotifyAfterCommitException.class)
            .hasMessageContaining("source transaction committed")
            .hasCauseInstanceOf(IllegalStateException.class);

        assertThat(status.isCompleted()).isTrue();
        verify(storage).notifyPartyRoleChanged(51L);
    }

    /**
     * Synchronization-only transaction manager so afterCommit / rollback can be exercised without
     * a DataSource. Commit and rollback themselves are no-ops on the resource.
     */
    private static final class ResourcelessTransactionManager extends AbstractPlatformTransactionManager {

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            // no resource to bind
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            // source commit already happened from the caller's point of view
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            // source rollback already happened from the caller's point of view
        }
    }
}
