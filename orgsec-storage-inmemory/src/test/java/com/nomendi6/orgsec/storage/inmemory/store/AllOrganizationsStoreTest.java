package com.nomendi6.orgsec.storage.inmemory.store;

import com.nomendi6.orgsec.model.OrganizationDef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AllOrganizationsStoreTest {

    private AllOrganizationsStore store;

    @BeforeEach
    void setUp() {
        store = new AllOrganizationsStore();
    }

    @Test
    void shouldReturnNullForNullOrganizationId() {
        OrganizationDef org = new OrganizationDef().setOrganizationId(1L).setOrganizationName("Acme");
        store.putOrganization(1L, org);

        assertThat(store.getOrganization(null)).isNull();
    }

    @Test
    void shouldReturnNullForNonExistentOrganization() {
        assertThat(store.getOrganization(999L)).isNull();
    }
}
