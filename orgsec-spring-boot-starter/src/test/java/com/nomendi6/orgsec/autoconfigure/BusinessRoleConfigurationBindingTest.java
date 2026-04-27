package com.nomendi6.orgsec.autoconfigure;

import com.nomendi6.orgsec.common.service.BusinessRoleConfiguration;
import com.nomendi6.orgsec.common.service.DefaultBusinessRoleProvider;
import com.nomendi6.orgsec.constants.SecurityFieldType;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class BusinessRoleConfigurationBindingTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
        .withBean(DefaultBusinessRoleProvider.class)
        .withUserConfiguration(BusinessRoleConfiguration.class);

    @Test
    void shouldBindRsqlFieldsWithCaseInsensitiveFieldKeys() {
        contextRunner
            .withPropertyValues(
                "orgsec.business-roles.owner.rsql-fields.company=ownerCompanyId",
                "orgsec.business-roles.owner.rsql-fields.Org=ownerOrgId"
            )
            .run(context -> {
                assertThat(context).hasSingleBean(BusinessRoleConfiguration.class);
                BusinessRoleConfiguration configuration = context.getBean(BusinessRoleConfiguration.class);

                assertThat(configuration.getRsqlFieldSelector("owner", SecurityFieldType.COMPANY)).isEqualTo("ownerCompanyId");
                assertThat(configuration.getRsqlFieldSelector("owner", SecurityFieldType.ORG)).isEqualTo("ownerOrgId");
                assertThat(configuration.getRsqlFieldSelector("owner", SecurityFieldType.PERSON)).isEqualTo("ownerPerson.id");
            });
    }
}
