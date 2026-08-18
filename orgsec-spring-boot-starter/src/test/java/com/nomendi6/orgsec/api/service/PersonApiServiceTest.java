package com.nomendi6.orgsec.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nomendi6.orgsec.api.dto.PersonApiDTO;
import com.nomendi6.orgsec.model.OrganizationDef;
import com.nomendi6.orgsec.model.PersonDef;
import com.nomendi6.orgsec.model.RoleDef;
import com.nomendi6.orgsec.provider.SecurityQueryProvider;
import com.nomendi6.orgsec.storage.SecurityDataStorage;
import com.nomendi6.orgsec.storage.inmemory.loader.PersonLoader;
import org.junit.jupiter.api.Test;

class PersonApiServiceTest {

    @Test
    void mapsMembershipAndPositionRolesNeededByTheKeycloakMapper() {
        SecurityDataStorage storage = mock(SecurityDataStorage.class);
        PersonDef person = new PersonDef(100L, "Owner admin")
            .setRelatedUserId("keycloak-user-id")
            .setRelatedUserLogin("ow-admin")
            .setDefaultCompanyId(1L)
            .setDefaultOrgunitId(111L);
        OrganizationDef membership = new OrganizationDef(
            "Owner company",
            1L,
            100L,
            "ow",
            "|root|ow|",
            1L,
            "|root|"
        );
        membership.addPositionRole(new RoleDef(100L, "OW_COMPANY_ADMIN"));
        person.organizationsMap.put(membership.organizationId, membership);
        when(storage.getPerson(100L)).thenReturn(person);

        PersonApiService service = new PersonApiService(
            storage,
            mock(SecurityQueryProvider.class),
            mock(PersonLoader.class)
        );

        PersonApiDTO result = service.getPersonById(100L);

        assertThat(result).isNotNull();
        assertThat(result.getVersion()).isEqualTo("1.0");
        assertThat(result.getRelatedUserId()).isEqualTo("keycloak-user-id");
        assertThat(result.getMemberships()).singleElement().satisfies(mapped -> {
            assertThat(mapped.getOrganizationId()).isEqualTo(1L);
            assertThat(mapped.getCompanyId()).isEqualTo(1L);
            assertThat(mapped.getPathId()).isEqualTo("ow");
            assertThat(mapped.getPositionRoleIds()).containsExactly(100L);
        });
    }
}
