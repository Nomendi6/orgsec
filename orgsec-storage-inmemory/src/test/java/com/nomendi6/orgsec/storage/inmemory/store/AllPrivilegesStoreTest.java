package com.nomendi6.orgsec.storage.inmemory.store;

import com.nomendi6.orgsec.constants.PrivilegeDirection;
import com.nomendi6.orgsec.constants.PrivilegeOperation;
import com.nomendi6.orgsec.model.PrivilegeDef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AllPrivilegesStoreTest {

    private AllPrivilegesStore store;

    @BeforeEach
    void setUp() {
        store = new AllPrivilegesStore();
    }

    @Test
    void shouldStartEmpty() {
        assertThat(store.size()).isZero();
        assertThat(store.getPrivilegesMap()).isEmpty();
        assertThat(store.getAllPrivilegeIdentifiers()).isEmpty();
    }

    @Test
    void shouldRegisterAndGetPrivilege() {
        // Given
        String identifier = "READ_USER";
        PrivilegeDef privilege = createReadPrivilege("READ_USER", "user");

        // When
        store.registerPrivilege(identifier, privilege);

        // Then
        assertThat(store.getPrivilege(identifier)).isEqualTo(privilege);
        assertThat(store.hasPrivilege(identifier)).isTrue();
        assertThat(store.size()).isEqualTo(1);
    }

    @Test
    void shouldReturnNullForNonExistentPrivilege() {
        assertThat(store.getPrivilege("NON_EXISTENT")).isNull();
        assertThat(store.hasPrivilege("NON_EXISTENT")).isFalse();
    }

    @Test
    void shouldPutAndGetPrivilege() {
        // Given
        String identifier = "WRITE_USER";
        PrivilegeDef privilege = createWritePrivilege("WRITE_USER", "user");

        // When
        store.putPrivilege(identifier, privilege);

        // Then
        assertThat(store.getPrivilege(identifier)).isEqualTo(privilege);
        assertThat(store.size()).isEqualTo(1);
    }

    @Test
    void shouldRegisterBulkPrivileges() {
        // Given
        Map<String, PrivilegeDef> privileges = new HashMap<>();
        privileges.put("READ_USER", createReadPrivilege("READ_USER", "user"));
        privileges.put("WRITE_USER", createWritePrivilege("WRITE_USER", "user"));
        privileges.put("READ_ORDER", createReadPrivilege("READ_ORDER", "order"));

        // When
        store.registerBulk(privileges);

        // Then
        assertThat(store.size()).isEqualTo(3);
        assertThat(store.hasPrivilege("READ_USER")).isTrue();
        assertThat(store.hasPrivilege("WRITE_USER")).isTrue();
        assertThat(store.hasPrivilege("READ_ORDER")).isTrue();
    }

    @Test
    void shouldGetAllPrivilegeIdentifiers() {
        // Given
        store.registerPrivilege("READ_USER", createReadPrivilege("READ_USER", "user"));
        store.registerPrivilege("WRITE_USER", createWritePrivilege("WRITE_USER", "user"));

        // When
        Set<String> identifiers = store.getAllPrivilegeIdentifiers();

        // Then
        assertThat(identifiers).containsExactlyInAnyOrder("READ_USER", "WRITE_USER");
    }

    @Test
    void shouldRemovePrivilege() {
        // Given
        store.registerPrivilege("READ_USER", createReadPrivilege("READ_USER", "user"));

        // When
        store.removePrivilege("READ_USER");

        // Then
        assertThat(store.hasPrivilege("READ_USER")).isFalse();
        assertThat(store.size()).isZero();
    }

    @Test
    void shouldClearAllPrivileges() {
        // Given
        store.registerPrivilege("READ_USER", createReadPrivilege("READ_USER", "user"));
        store.registerPrivilege("WRITE_USER", createWritePrivilege("WRITE_USER", "user"));

        // When
        store.clear();

        // Then
        assertThat(store.size()).isZero();
        assertThat(store.getAllPrivilegeIdentifiers()).isEmpty();
    }

    @Test
    void shouldSetPrivilegesMap() {
        // Given
        Map<String, PrivilegeDef> newMap = new HashMap<>();
        newMap.put("PRIV_1", createReadPrivilege("PRIV_1", "resource1"));
        newMap.put("PRIV_2", createWritePrivilege("PRIV_2", "resource2"));

        // When
        store.setPrivilegesMap(newMap);

        // Then
        assertThat(store.size()).isEqualTo(2);
        assertThat(store.getPrivilege("PRIV_1")).isNotNull();
        assertThat(store.getPrivilege("PRIV_2")).isNotNull();
    }

    @Test
    void shouldUpdateExistingPrivilege() {
        // Given
        String identifier = "USER_ACCESS";
        PrivilegeDef readPrivilege = createReadPrivilege(identifier, "user");
        PrivilegeDef writePrivilege = createWritePrivilege(identifier, "user");
        store.registerPrivilege(identifier, readPrivilege);

        // When
        store.registerPrivilege(identifier, writePrivilege);

        // Then
        assertThat(store.getPrivilege(identifier).operation).isEqualTo(PrivilegeOperation.WRITE);
        assertThat(store.size()).isEqualTo(1);
    }

    private PrivilegeDef createReadPrivilege(String name, String resourceName) {
        PrivilegeDef privilege = new PrivilegeDef(name, resourceName);
        privilege.operation = PrivilegeOperation.READ;
        privilege.org = PrivilegeDirection.EXACT;
        return privilege;
    }

    private PrivilegeDef createWritePrivilege(String name, String resourceName) {
        PrivilegeDef privilege = new PrivilegeDef(name, resourceName);
        privilege.operation = PrivilegeOperation.WRITE;
        privilege.org = PrivilegeDirection.EXACT;
        return privilege;
    }
}
