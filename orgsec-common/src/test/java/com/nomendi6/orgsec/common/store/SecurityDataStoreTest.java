package com.nomendi6.orgsec.common.store;

import com.nomendi6.orgsec.model.OrganizationDef;
import com.nomendi6.orgsec.model.PersonDef;
import com.nomendi6.orgsec.model.PrivilegeDef;
import com.nomendi6.orgsec.model.RoleDef;
import com.nomendi6.orgsec.storage.SecurityDataStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SecurityDataStoreTest {

    @Mock
    private SecurityDataStorage mockStorage;

    private SecurityDataStore store;

    @BeforeEach
    void setUp() {
        when(mockStorage.getProviderType()).thenReturn("test-provider");
        store = new SecurityDataStore(mockStorage);
    }

    // ========== PERSON OPERATIONS ==========

    @Test
    void shouldGetPerson() {
        // Given
        Long personId = 1L;
        PersonDef expectedPerson = new PersonDef(personId, "Test Person");
        when(mockStorage.getPerson(personId)).thenReturn(expectedPerson);

        // When
        PersonDef result = store.getPerson(personId);

        // Then
        assertThat(result).isEqualTo(expectedPerson);
        verify(mockStorage).getPerson(personId);
    }

    @Test
    void shouldReturnNullForNullPersonId() {
        assertThat(store.getPerson(null)).isNull();
        verify(mockStorage, never()).getPerson(any());
    }

    @Test
    void shouldReturnNullWhenPersonNotFound() {
        // Given
        when(mockStorage.getPerson(999L)).thenReturn(null);

        // When
        PersonDef result = store.getPerson(999L);

        // Then
        assertThat(result).isNull();
    }

    @Test
    void shouldPutPerson() {
        // Given
        Long personId = 1L;
        PersonDef person = new PersonDef(personId, "Test");

        // When
        store.putPerson(personId, person);

        // Then
        verify(mockStorage).updatePerson(personId, person);
    }

    @Test
    void shouldNotPutPersonWhenIdIsNull() {
        store.putPerson(null, new PersonDef(1L, "Test"));
        verify(mockStorage, never()).updatePerson(any(), any());
    }

    @Test
    void shouldNotPutPersonWhenPersonIsNull() {
        store.putPerson(1L, null);
        verify(mockStorage, never()).updatePerson(any(), any());
    }

    // ========== ORGANIZATION OPERATIONS ==========

    @Test
    void shouldGetOrganization() {
        // Given
        Long orgId = 1L;
        OrganizationDef expectedOrg = new OrganizationDef();
        expectedOrg.organizationId = orgId;
        when(mockStorage.getOrganization(orgId)).thenReturn(expectedOrg);

        // When
        OrganizationDef result = store.getOrganization(orgId);

        // Then
        assertThat(result).isEqualTo(expectedOrg);
        verify(mockStorage).getOrganization(orgId);
    }

    @Test
    void shouldReturnNullForNullOrgId() {
        assertThat(store.getOrganization(null)).isNull();
        verify(mockStorage, never()).getOrganization(any());
    }

    @Test
    void shouldPutOrganization() {
        // Given
        Long orgId = 1L;
        OrganizationDef org = new OrganizationDef();

        // When
        store.putOrganization(orgId, org);

        // Then
        verify(mockStorage).updateOrganization(orgId, org);
    }

    // ========== ROLE OPERATIONS ==========

    @Test
    void shouldGetPartyRole() {
        // Given
        Long roleId = 1L;
        RoleDef expectedRole = new RoleDef();
        expectedRole.roleId = roleId;
        when(mockStorage.getPartyRole(roleId)).thenReturn(expectedRole);

        // When
        RoleDef result = store.getPartyRole(roleId);

        // Then
        assertThat(result).isEqualTo(expectedRole);
        verify(mockStorage).getPartyRole(roleId);
    }

    @Test
    void shouldReturnNullForNullPartyRoleId() {
        assertThat(store.getPartyRole(null)).isNull();
        verify(mockStorage, never()).getPartyRole(any());
    }

    @Test
    void shouldGetPositionRole() {
        // Given
        Long roleId = 1L;
        RoleDef expectedRole = new RoleDef();
        expectedRole.roleId = roleId;
        when(mockStorage.getPositionRole(roleId)).thenReturn(expectedRole);

        // When
        RoleDef result = store.getPositionRole(roleId);

        // Then
        assertThat(result).isEqualTo(expectedRole);
        verify(mockStorage).getPositionRole(roleId);
    }

    @Test
    void shouldUpdateRole() {
        // Given
        Long roleId = 1L;
        RoleDef role = new RoleDef();

        // When
        store.updateRole(roleId, role);

        // Then
        verify(mockStorage).updateRole(roleId, role);
    }

    // ========== PRIVILEGE OPERATIONS ==========

    @Test
    void shouldGetPrivilege() {
        // Given
        String identifier = "READ_USER";
        PrivilegeDef expectedPrivilege = new PrivilegeDef("READ_USER", "user");
        when(mockStorage.getPrivilege(identifier)).thenReturn(expectedPrivilege);

        // When
        PrivilegeDef result = store.getPrivilege(identifier);

        // Then
        assertThat(result).isEqualTo(expectedPrivilege);
        verify(mockStorage).getPrivilege(identifier);
    }

    @Test
    void shouldReturnNullForNullPrivilegeIdentifier() {
        assertThat(store.getPrivilege(null)).isNull();
        verify(mockStorage, never()).getPrivilege(any());
    }

    // ========== STORAGE MANAGEMENT ==========

    @Test
    void shouldInitializeStorage() {
        // When
        store.initialize();

        // Then
        verify(mockStorage).initialize();
    }

    @Test
    void shouldThrowExceptionWhenInitializeFails() {
        // Given
        doThrow(new RuntimeException("Init failed")).when(mockStorage).initialize();

        // When/Then
        assertThatThrownBy(() -> store.initialize())
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("initialization failed");
    }

    @Test
    void shouldRefreshStorage() {
        // When
        store.refresh();

        // Then
        verify(mockStorage).refresh();
    }

    @Test
    void shouldCheckIfReady() {
        // Given
        when(mockStorage.isReady()).thenReturn(true);

        // When
        boolean result = store.isReady();

        // Then
        assertThat(result).isTrue();
        verify(mockStorage).isReady();
    }

    @Test
    void shouldGetStorageProviderType() {
        assertThat(store.getStorageProviderType()).isEqualTo("test-provider");
    }

    @Test
    void shouldGetUnderlyingStorage() {
        assertThat(store.getStorage()).isEqualTo(mockStorage);
    }

    // ========== ERROR HANDLING ==========

    @Test
    void shouldHandleExceptionOnGetPerson() {
        // Given
        when(mockStorage.getPerson(1L)).thenThrow(new RuntimeException("Storage error"));

        // When
        PersonDef result = store.getPerson(1L);

        // Then
        assertThat(result).isNull();
    }

    @Test
    void shouldHandleUnsupportedOperationOnPut() {
        // Given
        doThrow(new UnsupportedOperationException("Not supported"))
                .when(mockStorage).updatePerson(any(), any());

        // When - should not throw
        store.putPerson(1L, new PersonDef(1L, "Test"));

        // Then - method completed without exception
        verify(mockStorage).updatePerson(any(), any());
    }
}
