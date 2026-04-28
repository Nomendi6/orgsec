package com.nomendi6.orgsec.storage.inmemory.loader;

import com.nomendi6.orgsec.model.OrganizationDef;
import com.nomendi6.orgsec.storage.inmemory.store.AllOrganizationsStore;
import com.nomendi6.orgsec.storage.inmemory.store.AllRolesStore;
import jakarta.persistence.Tuple;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OrganizationLoaderTest {

    @Test
    void shouldLoadOrganizationsWithPipeFormattedPaths() {
        AllOrganizationsStore store = new AllOrganizationsStore();
        OrganizationLoader loader = new OrganizationLoader(new AllRolesStore(), store);

        loader.loadOrganizationsFromQueryResults(List.of(partyTuple("|1|10|", "|1|", "|1|")), List.of());

        OrganizationDef organization = store.getOrganization(10L);
        assertThat(organization.pathId).isEqualTo("|1|10|");
        assertThat(organization.parentPath).isEqualTo("|1|");
        assertThat(organization.companyParentPath).isEqualTo("|1|");
    }

    @Test
    void shouldPreserveLegacySegmentSemanticsWhenLoadingOrganizations() {
        AllOrganizationsStore store = new AllOrganizationsStore();
        OrganizationLoader loader = new OrganizationLoader(new AllRolesStore(), store);

        loader.loadOrganizationsFromQueryResults(List.of(partyTuple("ow", "|ow|", "|ow|")), List.of());

        OrganizationDef organization = store.getOrganization(10L);
        assertThat(organization.pathId).isEqualTo("ow");
        assertThat(organization.parentPath).isEqualTo("|ow|");
        assertThat(organization.companyParentPath).isEqualTo("|ow|");
    }

    private Tuple partyTuple(String pathId, String parentPath, String companyParentPath) {
        Tuple tuple = mock(Tuple.class);
        when(tuple.get("name", String.class)).thenReturn("Sales");
        when(tuple.get("id", Long.class)).thenReturn(10L);
        when(tuple.get("pathId", String.class)).thenReturn(pathId);
        when(tuple.get("parentPath", String.class)).thenReturn(parentPath);
        when(tuple.get("companyId", Long.class)).thenReturn(1L);
        when(tuple.get("companyParentPath", String.class)).thenReturn(companyParentPath);
        return tuple;
    }
}
