package com.nomendi6.orgsec.storage.inmemory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nomendi6.orgsec.common.service.BusinessRoleConfiguration;
import com.nomendi6.orgsec.common.service.BusinessRoleProvider;
import com.nomendi6.orgsec.common.service.DefaultBusinessRoleProvider;
import com.nomendi6.orgsec.common.service.PrivilegeChecker;
import com.nomendi6.orgsec.common.service.RsqlFilterBuilder;
import com.nomendi6.orgsec.common.service.SecurityEventPublisher;
import com.nomendi6.orgsec.common.store.SecurityDataStore;
import com.nomendi6.orgsec.constants.PrivilegeDirection;
import com.nomendi6.orgsec.constants.PrivilegeOperation;
import com.nomendi6.orgsec.constants.SecurityConstants;
import com.nomendi6.orgsec.constants.SecurityFieldType;
import com.nomendi6.orgsec.dto.OrganizationData;
import com.nomendi6.orgsec.dto.PersonData;
import com.nomendi6.orgsec.interfaces.SecurityEnabledDTO;
import com.nomendi6.orgsec.model.BusinessRoleDef;
import com.nomendi6.orgsec.model.OrganizationDef;
import com.nomendi6.orgsec.model.PersonDef;
import com.nomendi6.orgsec.model.PrivilegeDef;
import com.nomendi6.orgsec.model.ResourceDef;
import com.nomendi6.orgsec.provider.PersonDataProvider;
import com.nomendi6.orgsec.provider.SecurityContextProvider;
import com.nomendi6.orgsec.provider.UserDataProvider;
import com.nomendi6.orgsec.storage.SecurityDataStorage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

/**
 * The per-record check and the list filter must reach the same verdict from the same data.
 *
 * <p>Both used to consult the aggregated {@link PrivilegeDef} before looking at
 * {@link ResourceDef#getPrivilegesList()}, and they did it differently: the per-record path also
 * required the aggregate to satisfy the requested operation, the list path did not. A resource whose
 * list carried a READ privilege while its aggregate had been left at {@code NONE} was therefore
 * granted by the list filter and denied by the per-record check.
 *
 * <p>Both now decide from the list alone and fail closed when it is empty or absent.
 */
class GetListParityTest {

    private static final String RESOURCE = "document";
    private static final String ROLE = SecurityConstants.BusinessRoles.OWNER;
    private static final String LOGIN = "alice";
    private static final Long PERSON_ID = 1L;
    private static final Long PRINCIPAL_ORG_ID = 10L;
    private static final String PRINCIPAL_PATH = "|A|B|";

    private SecurityDataStorage storage;
    private PrivilegeSecurityService service;
    private RsqlFilterBuilder filterBuilder;
    private PersonData currentPerson;

    @BeforeEach
    void setUp() {
        storage = mock(SecurityDataStorage.class);
        currentPerson = new PersonData(PERSON_ID, "Alice");

        PersonDataProvider personDataProvider = mock(PersonDataProvider.class);
        when(personDataProvider.findByRelatedUserLogin(anyString())).thenReturn(Optional.of(currentPerson));

        SecurityContextProvider securityContextProvider = mock(SecurityContextProvider.class);
        when(securityContextProvider.getCurrentUserLogin()).thenReturn(Optional.of(LOGIN));

        BusinessRoleProvider roleProvider = new DefaultBusinessRoleProvider();
        BusinessRoleConfiguration roleConfiguration = new BusinessRoleConfiguration(List.of(roleProvider));
        roleConfiguration.initializeBusinessRoles();

        SecurityDataStore dataStore = new SecurityDataStore(storage);
        filterBuilder = new RsqlFilterBuilder(dataStore, roleConfiguration);
        service =
            new PrivilegeSecurityService(
                dataStore,
                new PrivilegeChecker(roleConfiguration),
                filterBuilder,
                mock(SecurityEventPublisher.class),
                personDataProvider,
                mock(UserDataProvider.class),
                securityContextProvider
            );
    }

    // --- the four states from the divergence table -------------------------------------------

    /** The row that used to diverge: list carries READ, aggregate was never updated. */
    @Test
    void listOnlyPrivilegeIsHonouredByBothPaths() {
        givenResource(resource -> resource.getPrivilegesList().add(orgPrivilege(PrivilegeDirection.HIERARCHY_DOWN)));

        assertThat(get(descendant())).as("per-record").isTrue();
        assertThat(list()).as("list filter").isNotBlank();
    }

    /**
     * 1.0.x keeps a compatibility fallback: an empty list falls back to the aggregate, so an
     * aggregate-only {@code all} privilege still grants. Both paths must agree on that. In 2.x this
     * case denies on both paths instead.
     */
    @Test
    void aggregateOnlyAllStillGrantsThroughTheCompatibilityFallback() {
        givenResource(resource -> {
            PrivilegeDef all = new PrivilegeDef(RESOURCE + "_ALL_R", RESOURCE)
                .allowOperation(PrivilegeOperation.READ)
                .allowAll(true);
            resource.setAggregatedReadPrivilege(all);
        });

        assertThat(get(descendant())).as("per-record").isTrue();
        assertThat(list()).as("list filter grants everything").isEmpty();
    }

    /** A scoped aggregate is honoured through the same 1.0.x fallback, on both paths. */
    @Test
    void aggregateOnlyScopedIsHonouredThroughTheCompatibilityFallback() {
        givenResource(resource -> resource.setAggregatedReadPrivilege(orgPrivilege(PrivilegeDirection.HIERARCHY_DOWN)));

        assertThat(get(descendant())).as("per-record").isTrue();
        assertThat(list()).as("list filter").isNotEmpty();
        // The fallback does not widen: a sibling branch is still denied.
        assertThat(get(entity(40L, "|A|X|"))).as("per-record sibling").isFalse();
    }

    /** A contradictory aggregate must not widen a restricted list. */
    @Test
    void contradictoryAggregateDoesNotOverrideTheList() {
        givenResource(resource -> {
            resource.getPrivilegesList().add(orgPrivilege(PrivilegeDirection.HIERARCHY_DOWN));
            PrivilegeDef all = new PrivilegeDef(RESOURCE + "_ALL_R", RESOURCE)
                .allowOperation(PrivilegeOperation.READ)
                .allowAll(true);
            resource.setAggregatedReadPrivilege(all);
        });

        // HIERARCHY_DOWN from |A|B| does not reach a sibling branch, and the aggregate must not
        // rescue it. An empty filter would mean "no filtering", i.e. every row.
        assertThat(get(entity(40L, "|A|X|"))).as("per-record").isFalse();
        assertThat(list()).as("list filter must still restrict").isNotEmpty().contains(PRINCIPAL_PATH);
    }

    /** With neither a list nor a usable aggregate, both paths deny. */
    @Test
    void emptyListAndEmptyAggregateDenyOnBothPaths() {
        givenResource(resource -> {});

        assertThat(get(descendant())).as("per-record").isFalse();
        assertThatThrownBy(this::list).as("list filter").isInstanceOf(AccessDeniedException.class);
    }

    /** A null list must not throw NullPointerException; with no usable aggregate it denies. */
    @Test
    void nullListDeniesOnBothPathsWithoutNullPointer() {
        givenResource(resource -> resource.setPrivilegesList(null));

        assertThat(get(descendant())).as("per-record").isFalse();
        assertThatThrownBy(this::list).as("list filter").isInstanceOf(AccessDeniedException.class);
    }

    // --- operations ---------------------------------------------------------------------------

    /** A WRITE privilege satisfies a READ request on both paths; the reverse does not hold. */
    @Test
    void writePrivilegeSatisfiesReadOnBothPaths() {
        givenResource(resource ->
            resource
                .getPrivilegesList()
                .add(
                    new PrivilegeDef(RESOURCE + "_ORGHD_W", RESOURCE)
                        .allowOperation(PrivilegeOperation.WRITE)
                        .allowOrg(PrivilegeDirection.NONE, PrivilegeDirection.HIERARCHY_DOWN, false)
                )
        );

        assertThat(get(descendant(), PrivilegeOperation.READ)).as("per-record READ").isTrue();
        assertThat(list(PrivilegeOperation.READ)).as("list READ").isNotBlank();
        assertThat(get(descendant(), PrivilegeOperation.WRITE)).as("per-record WRITE").isTrue();
        assertThat(list(PrivilegeOperation.WRITE)).as("list WRITE").isNotBlank();
    }

    /** A READ privilege never satisfies a WRITE or EXECUTE request, on either path. */
    @Test
    void readPrivilegeDoesNotSatisfyWriteOrExecuteOnBothPaths() {
        givenResource(resource -> resource.getPrivilegesList().add(orgPrivilege(PrivilegeDirection.HIERARCHY_DOWN)));

        assertThat(get(descendant(), PrivilegeOperation.WRITE)).as("per-record WRITE").isFalse();
        assertThatThrownBy(() -> list(PrivilegeOperation.WRITE)).as("list WRITE").isInstanceOf(AccessDeniedException.class);
        assertThat(get(descendant(), PrivilegeOperation.EXECUTE)).as("per-record EXECUTE").isFalse();
        assertThatThrownBy(() -> list(PrivilegeOperation.EXECUTE)).as("list EXECUTE").isInstanceOf(AccessDeniedException.class);
    }

    /** EXECUTE - the operation that triggered the original report - behaves identically. */
    @Test
    void executePrivilegeIsHonouredByBothPaths() {
        givenResource(resource ->
            resource
                .getPrivilegesList()
                .add(
                    new PrivilegeDef(RESOURCE + "_ORGHD_E", RESOURCE)
                        .allowOperation(PrivilegeOperation.EXECUTE)
                        .allowOrg(PrivilegeDirection.NONE, PrivilegeDirection.HIERARCHY_DOWN, false)
                )
        );

        assertThat(get(descendant(), PrivilegeOperation.EXECUTE)).as("per-record").isTrue();
        assertThat(list(PrivilegeOperation.EXECUTE)).as("list filter").isNotBlank();
    }

    // --- fixture ------------------------------------------------------------------------------

    private void givenResource(java.util.function.Consumer<ResourceDef> customizer) {
        ResourceDef resource = new ResourceDef(RESOURCE);
        resource.setPrivilegesList(new ArrayList<>());
        customizer.accept(resource);

        BusinessRoleDef role = new BusinessRoleDef(ROLE);
        role.resourcesMap.put(RESOURCE, resource);

        OrganizationDef organization = new OrganizationDef();
        organization.organizationId = PRINCIPAL_ORG_ID;
        organization.parentPath = PRINCIPAL_PATH;
        organization.businessRolesMap.put(ROLE, role);

        PersonDef person = new PersonDef(PERSON_ID, "Alice");
        person.organizationsMap.put(PRINCIPAL_ORG_ID, organization);

        when(storage.getPerson(PERSON_ID)).thenReturn(person);
    }

    private boolean get(SecurityEnabledDTO entity) {
        return get(entity, PrivilegeOperation.READ);
    }

    private boolean get(SecurityEnabledDTO entity, PrivilegeOperation operation) {
        return service.checkCurrentUserPrivilegeOnResource(entity, RESOURCE, operation);
    }

    private String list() {
        return list(PrivilegeOperation.READ);
    }

    private String list(PrivilegeOperation operation) {
        return filterBuilder.buildRsqlFilterForPrivileges(RESOURCE, null, null, operation, currentPerson);
    }

    private PrivilegeDef orgPrivilege(PrivilegeDirection org) {
        return new PrivilegeDef(RESOURCE + "_probe", RESOURCE)
            .allowOperation(PrivilegeOperation.READ)
            .allowOrg(PrivilegeDirection.NONE, org, false);
    }

    private SecurityEnabledDTO descendant() {
        return entity(30L, "|A|B|C|");
    }

    private SecurityEnabledDTO entity(Long orgId, String orgPath) {
        Map<SecurityFieldType, Object> fields = new HashMap<>();
        OrganizationData org = new OrganizationData();
        org.setId(orgId);
        fields.put(SecurityFieldType.ORG, org);
        fields.put(SecurityFieldType.ORG_PATH, orgPath);

        return new SecurityEnabledDTO() {
            @Override
            public Object getSecurityField(String businessRole, SecurityFieldType fieldType) {
                return ROLE.equalsIgnoreCase(businessRole) ? fields.get(fieldType) : null;
            }

            @Override
            public void setSecurityField(String businessRole, SecurityFieldType fieldType, Object value) {
                if (ROLE.equalsIgnoreCase(businessRole)) {
                    fields.put(fieldType, value);
                }
            }
        };
    }
}
