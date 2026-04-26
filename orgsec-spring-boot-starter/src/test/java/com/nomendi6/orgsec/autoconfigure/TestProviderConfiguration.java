package com.nomendi6.orgsec.autoconfigure;

import com.nomendi6.orgsec.dto.PersonData;
import com.nomendi6.orgsec.dto.UserData;
import com.nomendi6.orgsec.model.OrganizationDef;
import com.nomendi6.orgsec.model.PersonDef;
import com.nomendi6.orgsec.model.PrivilegeDef;
import com.nomendi6.orgsec.model.RoleDef;
import com.nomendi6.orgsec.provider.PersonDataProvider;
import com.nomendi6.orgsec.provider.SecurityQueryProvider;
import com.nomendi6.orgsec.provider.UserDataProvider;
import com.nomendi6.orgsec.storage.SecurityDataStorage;
import jakarta.persistence.Tuple;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@TestConfiguration
public class TestProviderConfiguration {

    /**
     * Mock SecurityDataStorage for testing without JPA dependencies.
     */
    @Bean
    @Primary
    public SecurityDataStorage mockSecurityDataStorage() {
        return new SecurityDataStorage() {
            @Override
            public PersonDef getPerson(Long personId) {
                return null;
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
            public void updatePerson(Long personId, PersonDef person) {
            }

            @Override
            public void updateOrganization(Long orgId, OrganizationDef organization) {
            }

            @Override
            public void updateRole(Long roleId, RoleDef role) {
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
                return "InMemoryTest";
            }

            @Override
            public void notifyPartyRoleChanged(Long roleId) {
            }

            @Override
            public void notifyPositionRoleChanged(Long roleId) {
            }

            @Override
            public void notifyOrganizationChanged(Long orgId) {
            }

            @Override
            public void notifyPersonChanged(Long personId) {
            }
        };
    }

    @Bean
    @Primary
    public SecurityQueryProvider mockSecurityQueryProvider() {
        return new SecurityQueryProvider() {
            @Override
            public List<Tuple> loadAllPartyRoles() {
                return new ArrayList<>();
            }

            @Override
            public List<Tuple> loadAllPartyRolePrivilegesAsStrings() {
                return new ArrayList<>();
            }

            @Override
            public List<Tuple> loadAllPositionRoles() {
                return new ArrayList<>();
            }

            @Override
            public List<Tuple> loadAllPositionRolePrivilegesAsStrings() {
                return new ArrayList<>();
            }

            @Override
            public List<Tuple> loadAllParties() {
                return new ArrayList<>();
            }

            @Override
            public List<Tuple> loadAllPartyAssignedRoles() {
                return new ArrayList<>();
            }

            @Override
            public List<Tuple> loadAllPersons() {
                return new ArrayList<>();
            }

            @Override
            public List<Tuple> loadAllPersonParties() {
                return new ArrayList<>();
            }

            @Override
            public List<Tuple> loadAllPersonPartyRoles() {
                return new ArrayList<>();
            }

            @Override
            public List<Tuple> loadAllPersonPositionRoles() {
                return new ArrayList<>();
            }

            @Override
            public List<Tuple> loadPersonById(Long personId) {
                return new ArrayList<>();
            }

            @Override
            public List<Tuple> loadPersonPartiesByPersonId(Long personId) {
                return new ArrayList<>();
            }

            @Override
            public List<Tuple> loadPersonPartyRolesByPersonId(Long personId) {
                return new ArrayList<>();
            }

            @Override
            public List<Tuple> loadPersonPositionRolesByPersonId(Long personId) {
                return new ArrayList<>();
            }

            @Override
            public List<Tuple> loadPartyById(Long partyId) {
                return new ArrayList<>();
            }

            @Override
            public List<Tuple> loadPartyAssignedRolesByPartyId(Long partyId) {
                return new ArrayList<>();
            }

            @Override
            public List<Tuple> loadPartyRoleById(Long roleId) {
                return new ArrayList<>();
            }

            @Override
            public List<Tuple> loadPartyRolePrivilegesByRoleIdAsStrings(Long roleId) {
                return new ArrayList<>();
            }

            @Override
            public List<Tuple> loadPositionRoleById(Long roleId) {
                return new ArrayList<>();
            }

            @Override
            public List<Tuple> loadPositionRolePrivilegesByRoleId(Long roleId) {
                return new ArrayList<>();
            }

            @Override
            public List<Tuple> loadPositionRolePrivilegesByRoleIdAsStrings(Long roleId) {
                return new ArrayList<>();
            }

            @Override
            public List<Tuple> loadPartiesRelatedToRole(Long partyRoleId) {
                return new ArrayList<>();
            }

            @Override
            public List<Tuple> loadPersonsRelatedToRole(Long partyRoleId) {
                return new ArrayList<>();
            }

            @Override
            public List<Tuple> loadPersonsRelatedToParty(Long partyId) {
                return new ArrayList<>();
            }

            @Override
            public List<Tuple> loadPersonsRelatedToPosition(Long positionId) {
                return new ArrayList<>();
            }

            @Override
            public List<Tuple> loadAllPartyRolePrivileges() {
                return new ArrayList<>();
            }

            @Override
            public List<Tuple> loadPartyRolePrivilegesByRoleId(Long roleId) {
                return new ArrayList<>();
            }

            @Override
            public List<Tuple> loadAllPositionRolePrivileges() {
                return new ArrayList<>();
            }
        };
    }

    @Bean
    @Primary
    public PersonDataProvider mockPersonDataProvider() {
        return new PersonDataProvider() {
            @Override
            public Optional<PersonData> findById(Long id) {
                return Optional.empty();
            }

            @Override
            public Optional<PersonData> findByRelatedUserLogin(String login) {
                return Optional.empty();
            }
        };
    }

    @Bean
    @Primary
    public UserDataProvider mockUserDataProvider() {
        return new UserDataProvider() {
            @Override
            public Optional<UserData> findByLogin(String login) {
                return Optional.empty();
            }

            @Override
            public Optional<String> getCurrentUserLogin() {
                return Optional.empty();
            }
        };
    }
}