package com.nomendi6.orgsec.autoconfigure;

import com.nomendi6.orgsec.model.OrganizationDef;
import com.nomendi6.orgsec.model.PersonDef;
import com.nomendi6.orgsec.model.PrivilegeDef;
import com.nomendi6.orgsec.model.RoleDef;
import com.nomendi6.orgsec.storage.SecurityDataStorage;

class StubSecurityDataStorage implements SecurityDataStorage {

    private final String providerType;

    StubSecurityDataStorage(String providerType) {
        this.providerType = providerType;
    }

    @Override
    public PersonDef getPerson(Long personId) {
        if (personId == null) {
            return null;
        }
        return new PersonDef(personId, providerType).setRelatedUserLogin(providerType);
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
    }

    @Override
    public void refresh() {
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
