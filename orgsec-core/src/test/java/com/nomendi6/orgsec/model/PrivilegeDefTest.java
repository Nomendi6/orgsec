package com.nomendi6.orgsec.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.nomendi6.orgsec.constants.PrivilegeDirection;
import com.nomendi6.orgsec.constants.PrivilegeOperation;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Direction algebra of {@link PrivilegeDef}.
 * <p>
 * A direction is a set of reachable organizations relative to the principal's organization X:
 * {@code EXACT(X) = {X}}, {@code HIERARCHY_DOWN(X) = {X and descendants}},
 * {@code HIERARCHY_UP(X) = {X and ancestors}}, {@code ALL = everything}. Summing two directions must
 * yield the narrowest direction covering both, and must never be wider than the true union.
 */
class PrivilegeDefTest {

    private static final String RESOURCE = "document";

    @Nested
    class DirectionAlgebra {

        @Test
        void nonePlusDirection_returnsTheOtherDirection() {
            assertThat(add(PrivilegeDirection.NONE, PrivilegeDirection.HIERARCHY_DOWN)).isEqualTo(PrivilegeDirection.HIERARCHY_DOWN);
            assertThat(add(PrivilegeDirection.HIERARCHY_UP, PrivilegeDirection.NONE)).isEqualTo(PrivilegeDirection.HIERARCHY_UP);
        }

        @Test
        void sameDirectionTwice_isIdempotent() {
            assertThat(add(PrivilegeDirection.HIERARCHY_DOWN, PrivilegeDirection.HIERARCHY_DOWN))
                .isEqualTo(PrivilegeDirection.HIERARCHY_DOWN);
            assertThat(add(PrivilegeDirection.HIERARCHY_UP, PrivilegeDirection.HIERARCHY_UP))
                .isEqualTo(PrivilegeDirection.HIERARCHY_UP);
            assertThat(add(PrivilegeDirection.EXACT, PrivilegeDirection.EXACT)).isEqualTo(PrivilegeDirection.EXACT);
        }

        @Test
        void exactPlusHierarchyDown_widensToHierarchyDown() {
            // EXACT(X) = {X} is a subset of HIERARCHY_DOWN(X) = {X and descendants}.
            assertThat(add(PrivilegeDirection.EXACT, PrivilegeDirection.HIERARCHY_DOWN)).isEqualTo(PrivilegeDirection.HIERARCHY_DOWN);
            assertThat(add(PrivilegeDirection.HIERARCHY_DOWN, PrivilegeDirection.EXACT)).isEqualTo(PrivilegeDirection.HIERARCHY_DOWN);
        }

        @Test
        void exactPlusHierarchyUp_widensToHierarchyUp() {
            // EXACT(X) = {X} is a subset of HIERARCHY_UP(X) = {X and ancestors}.
            assertThat(add(PrivilegeDirection.EXACT, PrivilegeDirection.HIERARCHY_UP)).isEqualTo(PrivilegeDirection.HIERARCHY_UP);
            assertThat(add(PrivilegeDirection.HIERARCHY_UP, PrivilegeDirection.EXACT)).isEqualTo(PrivilegeDirection.HIERARCHY_UP);
        }

        @Test
        void hierarchyDownPlusHierarchyUp_doesNotBecomeAll() {
            // Subtree plus ancestors excludes sibling and cousin branches, so ALL would over-grant.
            assertThat(add(PrivilegeDirection.HIERARCHY_DOWN, PrivilegeDirection.HIERARCHY_UP)).isEqualTo(PrivilegeDirection.EXACT);
            assertThat(add(PrivilegeDirection.HIERARCHY_UP, PrivilegeDirection.HIERARCHY_DOWN)).isEqualTo(PrivilegeDirection.EXACT);
        }

        @Test
        void allAbsorbsEveryOtherDirection() {
            assertThat(add(PrivilegeDirection.ALL, PrivilegeDirection.EXACT)).isEqualTo(PrivilegeDirection.ALL);
            assertThat(add(PrivilegeDirection.HIERARCHY_DOWN, PrivilegeDirection.ALL)).isEqualTo(PrivilegeDirection.ALL);
        }

        private PrivilegeDirection add(PrivilegeDirection a, PrivilegeDirection b) {
            return new PrivilegeDef("probe", RESOURCE).add(a, b);
        }
    }

    @Nested
    class PrivilegeAggregation {

        @Test
        void orgDownPlusOrgUp_staysOrgScopedAndNeverLeaksIntoCompany() {
            // A role holding both <RESOURCE>_ORGHD_E and <RESOURCE>_ORGHU_E must keep an org scope.
            // Leaking into company=EXACT would silently deny every principal on resources that carry
            // no company field, because the org check is skipped once org becomes NONE.
            PrivilegeDef down = orgPrivilege(PrivilegeDirection.HIERARCHY_DOWN, PrivilegeOperation.EXECUTE);
            PrivilegeDef up = orgPrivilege(PrivilegeDirection.HIERARCHY_UP, PrivilegeOperation.EXECUTE);

            PrivilegeDef combined = down.add(up);

            assertThat(combined.company).isEqualTo(PrivilegeDirection.NONE);
            assertThat(combined.org).isNotEqualTo(PrivilegeDirection.NONE);
            assertThat(combined.all).isFalse();
            assertThat(combined.name).doesNotContain("_COMP");
        }

        @Test
        void orgExactPlusOrgDown_keepsTheWiderOrgScope() {
            PrivilegeDef exact = orgPrivilege(PrivilegeDirection.EXACT, PrivilegeOperation.READ);
            PrivilegeDef down = orgPrivilege(PrivilegeDirection.HIERARCHY_DOWN, PrivilegeOperation.READ);

            PrivilegeDef combined = exact.add(down);

            assertThat(combined.org).isEqualTo(PrivilegeDirection.HIERARCHY_DOWN);
            assertThat(combined.company).isEqualTo(PrivilegeDirection.NONE);
            assertThat(combined.name).isEqualTo(RESOURCE + "_ORGHD_R");
        }

        @Test
        void writePlusRead_keepsWriteBecauseWriteImpliesRead() {
            PrivilegeDef write = orgPrivilege(PrivilegeDirection.EXACT, PrivilegeOperation.WRITE);
            PrivilegeDef read = orgPrivilege(PrivilegeDirection.EXACT, PrivilegeOperation.READ);

            assertThat(write.add(read).operation).isEqualTo(PrivilegeOperation.WRITE);
            assertThat(read.add(write).operation).isEqualTo(PrivilegeOperation.WRITE);
        }

        @Test
        void allFlagWins_andIsNotConfusedWithAllDirection() {
            PrivilegeDef all = new PrivilegeDef(RESOURCE + "_ALL_R", RESOURCE)
                .allowOperation(PrivilegeOperation.READ)
                .allowAll(true);
            PrivilegeDef org = orgPrivilege(PrivilegeDirection.HIERARCHY_DOWN, PrivilegeOperation.READ);

            PrivilegeDef combined = all.add(org);

            assertThat(combined.all).isTrue();
            assertThat(combined.name).isEqualTo(RESOURCE + "_ALL_R");
        }

        private PrivilegeDef orgPrivilege(PrivilegeDirection org, PrivilegeOperation operation) {
            return new PrivilegeDef(RESOURCE + "_probe", RESOURCE)
                .allowOperation(operation)
                .allowOrg(PrivilegeDirection.NONE, org, false);
        }
    }
}
