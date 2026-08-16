package com.nomendi6.orgsec.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nomendi6.orgsec.api.controller.PersonApiController;
import com.nomendi6.orgsec.provider.SecurityQueryProvider;
import com.nomendi6.orgsec.storage.SecurityDataStorage;
import com.nomendi6.orgsec.storage.inmemory.StorageConfiguration;
import com.nomendi6.orgsec.storage.inmemory.loader.PersonLoader;
import com.nomendi6.orgsec.storage.inmemory.store.AllPersonsStore;
import com.nomendi6.orgsec.storage.inmemory.store.AllRolesStore;
import jakarta.persistence.Tuple;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    classes = PersonApiSecurityChainTest.TestApp.class,
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = {
        "orgsec.api.person.enabled=true",
        "orgsec.api.person.required-role=ORGSEC_API_CLIENT"
    }
)
@AutoConfigureMockMvc
class PersonApiSecurityChainTest {

    private static final String EXISTING_USER = "8f2b1c34-0000-4000-8000-000000000001";
    private static final String MISSING_USER = "8f2b1c34-0000-4000-8000-000000000404";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ApplicationContext context;

    @Test
    void libraryOwnsExactlyOneControllerAndNamedSecurityChain() {
        assertThat(context.getBeansOfType(PersonApiController.class)).hasSize(1);
        assertThat(context.containsBean("orgsecApiSecurityFilterChain")).isTrue();
    }

    @Test
    void anonymousRequestIs401() throws Exception {
        mockMvc.perform(get(byUser(EXISTING_USER))).andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @ValueSource(strings = {"bad-signature", "expired", "wrong-audience"})
    void invalidBearerTokenIs401(String token) throws Exception {
        mockMvc
            .perform(get(byUser(EXISTING_USER)).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void authenticatedCallerWithoutRequiredRealmRoleIs403() throws Exception {
        mockMvc
            .perform(get(byUser(EXISTING_USER)).header(HttpHeaders.AUTHORIZATION, "Bearer without-role"))
            .andExpect(status().isForbidden());
    }

    @Test
    void serviceAccountRealmRoleAndExistingUserReturn200FromDelegate() throws Exception {
        mockMvc
            .perform(get(byUser(EXISTING_USER)).header(HttpHeaders.AUTHORIZATION, "Bearer with-role"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("inmemory-delegate"));
    }

    @Test
    void serviceAccountRealmRoleAndUnknownUserReturn404() throws Exception {
        mockMvc
            .perform(get(byUser(MISSING_USER)).header(HttpHeaders.AUTHORIZATION, "Bearer with-role"))
            .andExpect(status().isNotFound());
    }

    private static String byUser(String userId) {
        return "/api/orgsec/person/by-user/" + userId;
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = StorageConfiguration.class)
    static class TestApp {

        @Bean
        AllPersonsStore allPersonsStore() {
            return new AllPersonsStore();
        }

        @Bean
        AllRolesStore allRolesStore() {
            return new AllRolesStore();
        }

        @Bean
        PersonLoader personLoader(AllRolesStore roles, AllPersonsStore persons) {
            return new PersonLoader(roles, persons);
        }

        @Bean
        SecurityQueryProvider securityQueryProvider() {
            SecurityQueryProvider provider = mock(SecurityQueryProvider.class);
            Tuple tuple = mock(Tuple.class);
            when(tuple.get("personId", Long.class)).thenReturn(1L);
            when(provider.loadPersonByUserId(EXISTING_USER)).thenReturn(List.of(tuple));
            when(provider.loadPersonByUserId(MISSING_USER)).thenReturn(List.of());
            return provider;
        }

        @Bean
        @Primary
        SecurityDataStorage jwtPrimaryStorage() {
            return new StubSecurityDataStorage("jwt-primary");
        }

        @Bean("delegateSecurityDataStorage")
        SecurityDataStorage delegateSecurityDataStorage() {
            return new StubSecurityDataStorage("inmemory-delegate");
        }

        @Bean
        JwtDecoder jwtDecoder() {
            return token -> switch (token) {
                case "with-role" -> jwt(token, List.of("ORGSEC_API_CLIENT", "offline_access"));
                case "without-role" -> jwt(token, List.of("offline_access"));
                case "expired" -> throw invalidToken("Token expired");
                case "wrong-audience" -> throw invalidToken("The aud claim is not valid");
                default -> throw new BadJwtException("Invalid signature or malformed token");
            };
        }

        private static JwtValidationException invalidToken(String message) {
            return new JwtValidationException(
                message,
                List.of(new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN, message, null))
            );
        }

        private static Jwt jwt(String tokenValue, List<String> roles) {
            Instant now = Instant.now();
            return Jwt.withTokenValue(tokenValue)
                .header("alg", "none")
                .subject("service-account-orgsec-mapper")
                .claim("realm_access", Map.of("roles", roles))
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300))
                .build();
        }
    }
}
