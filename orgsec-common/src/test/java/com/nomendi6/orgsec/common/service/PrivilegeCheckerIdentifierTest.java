package com.nomendi6.orgsec.common.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.nomendi6.orgsec.constants.PrivilegeDirection;
import com.nomendi6.orgsec.constants.PrivilegeOperation;
import com.nomendi6.orgsec.constants.SecurityConstants;
import com.nomendi6.orgsec.constants.SecurityFieldType;
import com.nomendi6.orgsec.dto.PersonData;
import com.nomendi6.orgsec.interfaces.SecurityEnabledDTO;
import com.nomendi6.orgsec.model.OrganizationDef;
import com.nomendi6.orgsec.model.PrivilegeDef;
import java.math.BigInteger;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * What an entity's {@code getId()} is allowed to return.
 *
 * <p>The reflective path cast the result straight to {@code Long}. An entity exposing an
 * {@code Integer} id - which both JPA and MapStruct produce for an {@code int} column - therefore
 * raised a {@link ClassCastException}, which was swallowed into a {@code null}, and the record was
 * denied. The denial looked like an authorization decision and was really a type mismatch three
 * frames away.
 *
 * <p><strong>This widens access:</strong> records previously refused because of the box their id
 * arrived in are now evaluated on their merits. Non-integral ids are still refused rather than
 * rounded - a decimal id is a mapping error, and truncating it would compare against a different row.
 */
class PrivilegeCheckerIdentifierTest {

    private static final String OWNER = SecurityConstants.BusinessRoles.OWNER;
    private static final Long PRINCIPAL_ORG = 10L;

    private PrivilegeChecker privilegeChecker;
    private PersonData currentPerson;

    @BeforeEach
    void setUp() {
        BusinessRoleConfiguration roleConfiguration = new BusinessRoleConfiguration(List.of(new DefaultBusinessRoleProvider()));
        roleConfiguration.initializeBusinessRoles();
        privilegeChecker = new PrivilegeChecker(roleConfiguration);
        currentPerson = new PersonData(1L, "Alice");
    }

    @Test
    void aLongIdIsAcceptedAsItAlwaysWas() {
        assertThat(checkOrgExact(new IdHolder(10L))).isTrue();
    }

    @Test
    void anIntegerIdIsNowAcceptedInsteadOfBeingDenied() {
        // Before 1.0.5 this denied: (Long) on an Integer threw, the catch turned it into null, and a
        // null record id can never match.
        assertThat(checkOrgExact(new IdHolder(10))).isTrue();
    }

    @Test
    void shortAndByteIdsAreAcceptedToo() {
        assertThat(checkOrgExact(new IdHolder((short) 10))).isTrue();
        assertThat(checkOrgExact(new IdHolder((byte) 10))).isTrue();
    }

    @Test
    void aBigIntegerInRangeIsAccepted() {
        assertThat(checkOrgExact(new IdHolder(BigInteger.valueOf(10L)))).isTrue();
    }

    @Test
    void aBigIntegerOutOfLongRangeIsRefused() {
        BigInteger tooBig = BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE);

        assertThat(checkOrgExact(new IdHolder(tooBig))).isFalse();
    }

    @Test
    void aDecimalIdIsRefusedRatherThanTruncated() {
        // 10.0 and 10.7 must both deny: rounding either one would compare against a row the entity
        // does not identify.
        assertThat(checkOrgExact(new IdHolder(10.0d))).isFalse();
        assertThat(checkOrgExact(new IdHolder(10.7d))).isFalse();
        assertThat(checkOrgExact(new IdHolder(new java.math.BigDecimal("10.0")))).isFalse();
    }

    @Test
    void aNonNumericIdIsRefused() {
        assertThat(checkOrgExact(new IdHolder("10"))).isFalse();
    }

    @Test
    void anAbsentIdIsRefused() {
        assertThat(checkOrgExact(new IdHolder(null))).isFalse();
        assertThat(checkOrgExact(null)).isFalse();
    }

    @Test
    void aDifferentIdStillDeniesWhateverItsType() {
        assertThat(checkOrgExact(new IdHolder(999))).isFalse();
        assertThat(checkOrgExact(new IdHolder(999L))).isFalse();
    }

    // --- fixture ------------------------------------------------------------------------------

    private boolean checkOrgExact(Object orgField) {
        OrganizationDef principal = new OrganizationDef();
        principal.organizationId = PRINCIPAL_ORG;
        principal.companyId = 1L;
        principal.parentPath = "|A|B|";
        principal.companyParentPath = "|A|";

        PrivilegeDef privilege = new PrivilegeDef("probe", "document")
            .allowOperation(PrivilegeOperation.READ)
            .allowOrg(PrivilegeDirection.NONE, PrivilegeDirection.EXACT, false);

        return privilegeChecker.checkBusinessRolePrivilege(currentPerson, principal, privilege, OWNER, new OrgOnlyDto(orgField));
    }

    /** Stands in for a domain DTO: only reachable reflectively, exactly as in an application. */
    public static class IdHolder {

        private final Object id;

        IdHolder(Object id) {
            this.id = id;
        }

        public Object getId() {
            return id;
        }
    }

    private record OrgOnlyDto(Object orgField) implements SecurityEnabledDTO {
        @Override
        public Object getSecurityField(String businessRole, SecurityFieldType fieldType) {
            if (!OWNER.equalsIgnoreCase(businessRole)) {
                return null;
            }
            return fieldType == SecurityFieldType.ORG ? orgField : null;
        }

        @Override
        public void setSecurityField(String businessRole, SecurityFieldType fieldType, Object value) {
            throw new UnsupportedOperationException();
        }
    }
}
