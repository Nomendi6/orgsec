package com.nomendi6.orgsec.storage.redis.testutil;

import com.nomendi6.orgsec.model.OrganizationDef;
import com.nomendi6.orgsec.model.PersonDef;

/**
 * Test data builder for creating test instances of domain objects.
 */
public class TestDataBuilder {

    /**
     * Builds a sample PersonDef for testing.
     *
     * @return PersonDef instance
     */
    public static PersonDef buildPerson() {
        return buildPerson(1L, "Test Person");
    }

    /**
     * Builds a PersonDef with specified ID and name.
     *
     * @param personId   the person ID
     * @param personName the person name
     * @return PersonDef instance
     */
    public static PersonDef buildPerson(Long personId, String personName) {
        PersonDef person = new PersonDef(personId, personName);
        person.setRelatedUserId("user-" + personId);
        person.setRelatedUserLogin("user" + personId + "@test.com");
        person.setDefaultCompanyId(100L);
        person.setDefaultOrgunitId(200L);
        return person;
    }

    /**
     * Builds a PersonDef with organizations.
     *
     * @param personId the person ID
     * @return PersonDef instance with organizations
     */
    public static PersonDef buildPersonWithOrganizations(Long personId) {
        PersonDef person = buildPerson(personId, "Person " + personId);

        OrganizationDef org1 = buildOrganization(1L, "Organization 1", null);
        OrganizationDef org2 = buildOrganization(2L, "Organization 2", "/1");

        person.organizationsMap.put(org1.organizationId, org1);
        person.organizationsMap.put(org2.organizationId, org2);

        return person;
    }

    /**
     * Builds a sample OrganizationDef for testing.
     *
     * @return OrganizationDef instance
     */
    public static OrganizationDef buildOrganization() {
        return buildOrganization(1L, "Test Organization", null);
    }

    /**
     * Builds an OrganizationDef with specified parameters.
     *
     * @param orgId     the organization ID
     * @param orgName   the organization name
     * @param parentPath the parent organization path (can be null)
     * @return OrganizationDef instance
     */
    public static OrganizationDef buildOrganization(Long orgId, String orgName, String parentPath) {
        OrganizationDef org = new OrganizationDef();
        org.organizationId = orgId;
        org.organizationName = orgName;
        org.parentPath = parentPath;
        org.pathId = parentPath == null ? "/" + orgId : parentPath + "/" + orgId;
        return org;
    }
}
