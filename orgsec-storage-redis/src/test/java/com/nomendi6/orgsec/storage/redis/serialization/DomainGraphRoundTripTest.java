package com.nomendi6.orgsec.storage.redis.serialization;

import static org.assertj.core.api.Assertions.assertThat;

import com.nomendi6.orgsec.constants.PrivilegeDirection;
import com.nomendi6.orgsec.constants.PrivilegeOperation;
import com.nomendi6.orgsec.model.BusinessRoleDef;
import com.nomendi6.orgsec.model.OrganizationDef;
import com.nomendi6.orgsec.model.PersonDef;
import com.nomendi6.orgsec.model.PrivilegeDef;
import com.nomendi6.orgsec.model.ResourceDef;
import com.nomendi6.orgsec.model.RoleDef;
import com.nomendi6.orgsec.storage.redis.config.RedisStorageProperties;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The L2 cache stores four types, and three of them reach {@link ResourceDef} or
 * {@link BusinessRoleDef}. Neither had a no-arg constructor, so Jackson found no usable creator and
 * every one of those three failed to deserialize - the role cache silently never returned anything
 * usable. Only {@link PrivilegeDef}, a leaf, round-tripped.
 *
 * <p>On the 1.0.x line Jackson 2 throws a checked {@link JsonProcessingException}; the 2.x line uses
 * Jackson 3, where it is unchecked and the {@code throws} clauses are absent.
 *
 * <p>These tests exercise all three entry points with populated collections, because an empty
 * {@code resourcesMap} serializes to <code>{}</code> and comes back fine - which is exactly why the
 * existing Redis tests never caught this.
 */
class DomainGraphRoundTripTest {

    private static final String RESOURCE = "PAYMENT_ORDER";
    private static final String BUSINESS_ROLE = "owner";

    private static ObjectMapper standardMapper() {
        return new OrgsecObjectMapperFactory().getDomainObjectMapper();
    }

    private static ObjectMapper strictMapper() {
        RedisStorageProperties.SerializationConfig config = new RedisStorageProperties.SerializationConfig();
        config.setStrictMode(true);
        return new OrgsecObjectMapperFactory(config).getDomainObjectMapper();
    }

    private static RoleDef roleWithPrivileges() {
        RoleDef role = new RoleDef(1L, "ODOBRAVATELJ");
        role.addPrivilegeDef(
            new PrivilegeDef(RESOURCE + "_ORGHD_E", RESOURCE)
                .allowOperation(PrivilegeOperation.EXECUTE)
                .allowOrg(PrivilegeDirection.NONE, PrivilegeDirection.HIERARCHY_DOWN, false)
        );
        role.addPrivilegeDef(
            new PrivilegeDef(RESOURCE + "_ORGHU_E", RESOURCE)
                .allowOperation(PrivilegeOperation.EXECUTE)
                .allowOrg(PrivilegeDirection.NONE, PrivilegeDirection.HIERARCHY_UP, false)
        );
        return role;
    }

    private static OrganizationDef organizationWithBusinessRole() {
        OrganizationDef organization = new OrganizationDef();
        organization.organizationId = 10L;
        organization.parentPath = "|A|B|";

        BusinessRoleDef businessRole = new BusinessRoleDef(BUSINESS_ROLE);
        businessRole.resourcesMap.put(RESOURCE, roleWithPrivileges().resourcesMap.get(RESOURCE));
        organization.businessRolesMap.put(BUSINESS_ROLE, businessRole);
        organization.addPositionRole(roleWithPrivileges());

        return organization;
    }

    // --- entry point 1: RoleDef -> resourcesMap -> ResourceDef --------------------------------

    @Test
    void roleDefSurvivesRoundTripWithItsResources() throws JsonProcessingException {
        ObjectMapper mapper = standardMapper();
        RoleDef before = roleWithPrivileges();
        assertThat(before.resourcesMap.get(RESOURCE).getPrivilegesList()).hasSize(2);

        RoleDef after = mapper.readValue(mapper.writeValueAsString(before), RoleDef.class);

        ResourceDef resource = after.resourcesMap.get(RESOURCE);
        assertThat(resource).isNotNull();
        assertThat(resource.getPrivilegesList())
            .as("privilegesList is what both authorization paths iterate")
            .hasSize(2)
            .extracting(privilege -> privilege.name)
            .containsExactlyInAnyOrder(RESOURCE + "_ORGHD_E", RESOURCE + "_ORGHU_E");
    }

    // --- entry point 2: OrganizationDef -> businessRolesMap -> BusinessRoleDef ----------------

    @Test
    void organizationDefSurvivesRoundTripWithItsBusinessRolesAndRoles() throws JsonProcessingException {
        ObjectMapper mapper = standardMapper();
        OrganizationDef before = organizationWithBusinessRole();

        OrganizationDef after = mapper.readValue(mapper.writeValueAsString(before), OrganizationDef.class);

        assertThat(after.businessRolesMap).containsKey(BUSINESS_ROLE);
        assertThat(after.businessRolesMap.get(BUSINESS_ROLE).resourcesMap.get(RESOURCE).getPrivilegesList()).hasSize(2);
        assertThat(after.positionRolesSet).hasSize(1);
        assertThat(after.positionRolesSet.iterator().next().resourcesMap.get(RESOURCE).getPrivilegesList()).hasSize(2);
    }

    // --- entry point 3: PersonDef -> organizationsMap -> OrganizationDef ----------------------

    @Test
    void personDefSurvivesRoundTripWithItsOrganizations() throws JsonProcessingException {
        ObjectMapper mapper = standardMapper();
        PersonDef before = new PersonDef(1L, "Alice");
        before.organizationsMap.put(10L, organizationWithBusinessRole());

        PersonDef after = mapper.readValue(mapper.writeValueAsString(before), PersonDef.class);

        OrganizationDef organization = after.organizationsMap.get(10L);
        assertThat(organization).isNotNull();
        assertThat(organization.businessRolesMap.get(BUSINESS_ROLE).resourcesMap.get(RESOURCE).getPrivilegesList()).hasSize(2);
    }

    // --- the same must hold for the strict mapper ---------------------------------------------

    @Test
    void strictMapperRoundTripsTheWholeGraphToo() throws JsonProcessingException {
        ObjectMapper mapper = strictMapper();
        PersonDef before = new PersonDef(1L, "Alice");
        before.organizationsMap.put(10L, organizationWithBusinessRole());

        PersonDef after = mapper.readValue(mapper.writeValueAsString(before), PersonDef.class);

        assertThat(after.organizationsMap.get(10L).businessRolesMap.get(BUSINESS_ROLE).resourcesMap.get(RESOURCE).getPrivilegesList())
            .hasSize(2);
    }

    /** A deserialized ResourceDef must never hand a null list to the authorization path. */
    @Test
    void deserializedResourceDefNeverHasANullPrivilegesList() throws JsonProcessingException {
        ObjectMapper mapper = standardMapper();

        ResourceDef after = mapper.readValue("{\"resourceName\":\"" + RESOURCE + "\"}", ResourceDef.class);

        assertThat(after.getPrivilegesList()).isNotNull().isEmpty();
    }
}
