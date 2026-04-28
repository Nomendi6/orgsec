package com.nomendi6.orgsec.storage.inmemory.store;

import com.nomendi6.orgsec.model.RoleDef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AllRolesStoreTest {

    private AllRolesStore store;

    @BeforeEach
    void setUp() {
        store = new AllRolesStore();
    }

    @Test
    void shouldReturnNullForNullOrganizationRoleId() {
        store.putOrganizationRole(1L, new RoleDef(1L, "MANAGER"));

        assertThat(store.getOrganizationRole((Long) null)).isNull();
    }

    @Test
    void shouldReturnNullForNullPositionRoleId() {
        store.putPositionRole(1L, new RoleDef(1L, "POSITION"));

        assertThat(store.getPositionRole(null)).isNull();
    }

    @Test
    void shouldReturnNullForNullOrganizationRoleCode() {
        store.putOrganizationRole(1L, new RoleDef(1L, "MANAGER"));

        assertThat(store.getOrganizationRole((String) null)).isNull();
    }

    @Test
    void shouldReturnNullForUnknownRoleCode() {
        assertThat(store.getOrganizationRole("UNKNOWN")).isNull();
    }
}
