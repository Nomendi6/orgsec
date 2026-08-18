package com.nomendi6.orgsec.helper;

import static org.assertj.core.api.Assertions.assertThat;

import com.nomendi6.orgsec.model.OrganizationDef;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LineageBuilderTest {

    @Test
    void rootHasSelfOnlyAndCompanyLineageIsTheCompanyWalk() {
        Map<Long, OrganizationDef> orgs = new LinkedHashMap<>();
        orgs.put(1L, org(1L, null, 1L));
        orgs.put(10L, org(10L, 1L, 1L));
        orgs.put(15L, org(15L, 10L, 1L));

        LineageBuilder.assignLineages(orgs);

        assertThat(orgs.get(1L).orgLineageIds).containsExactly(1L);
        assertThat(orgs.get(10L).orgLineageIds).containsExactly(1L, 10L);
        assertThat(orgs.get(15L).orgLineageIds).containsExactly(1L, 10L, 15L);
        assertThat(orgs.get(15L).companyLineageIds).containsExactly(1L);
        assertThat(orgs.get(15L).companyLineageIds).isSameAs(orgs.get(1L).orgLineageIds);
    }

    @Test
    void missingParentRejectsThatBranch() {
        Map<Long, OrganizationDef> orgs = new LinkedHashMap<>();
        orgs.put(15L, org(15L, 10L, 1L));

        LineageBuilder.assignLineages(orgs);

        assertThat(orgs.get(15L).orgLineageIds).isNull();
        assertThat(orgs.get(15L).companyLineageIds).isNull();
    }

    @Test
    void cycleRejectsTheCycle() {
        Map<Long, OrganizationDef> orgs = new LinkedHashMap<>();
        orgs.put(1L, org(1L, 2L, 1L));
        orgs.put(2L, org(2L, 1L, 1L));

        LineageBuilder.assignLineages(orgs);

        assertThat(orgs.get(1L).orgLineageIds).isNull();
        assertThat(orgs.get(2L).orgLineageIds).isNull();
    }

    @Test
    void usableRequiresInclusiveSelfAndNoDuplicates() {
        assertThat(LineageBuilder.isUsable(List.of(1L, 10L, 15L), 15L)).isTrue();
        assertThat(LineageBuilder.isUsable(List.of(1L, 10L, 15L), 10L)).isFalse();
        assertThat(LineageBuilder.isUsable(List.of(), 1L)).isFalse();
        assertThat(LineageBuilder.isUsable(null, 1L)).isFalse();
        assertThat(LineageBuilder.isUsable(List.of(1L, 1L), 1L)).isFalse();
    }

    private static OrganizationDef org(long id, Long parentId, Long companyId) {
        OrganizationDef organization = new OrganizationDef();
        organization.organizationId = id;
        organization.parentId = parentId;
        organization.companyId = companyId;
        return organization;
    }
}
