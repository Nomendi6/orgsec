package com.nomendi6.orgsec.autoconfigure;

import com.nomendi6.orgsec.model.OrganizationDef;
import com.nomendi6.orgsec.model.PersonDef;
import com.nomendi6.orgsec.model.PrivilegeDef;
import com.nomendi6.orgsec.model.RoleDef;
import com.nomendi6.orgsec.storage.SecurityDataStorage;

/**
 * Storage stub whose only job is to be identifiable. Tests register two of them - one
 * {@code @Primary}, one under the {@code delegateSecurityDataStorage} name - and then check
 * which one a collaborator actually reached.
 */
class StubSecurityDataStorage implements SecurityDataStorage {

    private final String providerType;

    StubSecurityDataStorage(String providerType) {
        this.providerType = providerType;
    }

    /**
     * Returns a person whose name is this stub's provider type, so the answer identifies its
     * source all the way out to the HTTP response body.
     */
    @Override
    public PersonDef getPerson(Long personId) {
        if (personId == null) {
            return null;
        }
        PersonDef person = new PersonDef(personId, providerType);
        person.setRelatedUserLogin(providerType);
        return person;
    }

    @Override
    public OrganizationDef getOrganization(Long orgId) {
        return null;
    }

    @Override
    public RoleDef getPartyRole(Long roleId) {
        return null;
    }

    @Override
    public RoleDef getPositionRole(Long roleId) {
        return null;
    }

    @Override
    public PrivilegeDef getPrivilege(String privilegeIdentifier) {
        return null;
    }

    @Override
    public void initialize() {
        // nothing to load
    }

    @Override
    public void refresh() {
        // nothing to reload
    }

    @Override
    public boolean isReady() {
        return true;
    }

    @Override
    public String getProviderType() {
        return providerType;
    }
}
