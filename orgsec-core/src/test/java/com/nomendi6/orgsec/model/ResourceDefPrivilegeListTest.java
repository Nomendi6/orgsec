package com.nomendi6.orgsec.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.nomendi6.orgsec.constants.PrivilegeOperation;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@code privilegesList} is what both evaluators read to make a decision, so the setter must not
 * leave the caller holding a handle on it.
 *
 * <p>Storing the argument by reference meant a later {@code add} on the caller's collection changed
 * what a stored, shared {@code ResourceDef} grants - without going through any OrgSec code. Setting
 * it to {@code null} was worse: both consumers iterate the list unconditionally, so the next
 * authorization decision raised a {@link NullPointerException} instead of denying.
 */
class ResourceDefPrivilegeListTest {

    @Test
    void setterTakesACopySoLaterMutationOfTheArgumentDoesNotLeakIn() {
        List<PrivilegeDef> caller = new ArrayList<>();
        caller.add(privilege("DOCUMENT_ORG_R"));

        ResourceDef resource = new ResourceDef("DOCUMENT");
        resource.setPrivilegesList(caller);

        caller.add(privilege("DOCUMENT_ALL_RW"));

        assertThat(resource.getPrivilegesList())
            .extracting(privilege -> privilege.name)
            .containsExactly("DOCUMENT_ORG_R");
    }

    @Test
    void nullClearsTheListInsteadOfInstallingOne() {
        ResourceDef resource = new ResourceDef("DOCUMENT");
        resource.setPrivilegesList(List.of(privilege("DOCUMENT_ORG_R")));

        resource.setPrivilegesList(null);

        assertThat(resource.getPrivilegesList()).isNotNull().isEmpty();
    }

    @Test
    void theCopyIsStillMutableForCallersThatBuildTheListInPlace() {
        // The library itself does resource.getPrivilegesList().add(...); an immutable copy would
        // break that.
        ResourceDef resource = new ResourceDef("DOCUMENT");
        resource.setPrivilegesList(List.of(privilege("DOCUMENT_ORG_R")));

        resource.getPrivilegesList().add(privilege("DOCUMENT_ORGHD_R"));

        assertThat(resource.getPrivilegesList()).hasSize(2);
    }

    private static PrivilegeDef privilege(String name) {
        return new PrivilegeDef(name, "DOCUMENT").allowOperation(PrivilegeOperation.READ);
    }
}
