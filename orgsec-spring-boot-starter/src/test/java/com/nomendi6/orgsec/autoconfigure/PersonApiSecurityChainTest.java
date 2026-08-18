package com.nomendi6.orgsec.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nomendi6.orgsec.api.service.PersonApiService;
import com.nomendi6.orgsec.provider.SecurityQueryProvider;
import com.nomendi6.orgsec.storage.SecurityDataStorage;
import com.nomendi6.orgsec.storage.inmemory.loader.PersonLoader;
import com.nomendi6.orgsec.storage.inmemory.store.AllPersonsStore;
import com.nomendi6.orgsec.storage.inmemory.store.AllRolesStore;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * HTTP contract of the Person API chain.
 *
 * <p>Before 1.0.5 the chain authorized with {@code hasRole(...)} but never installed an
 * authentication mechanism, so whether {@code /api/orgsec/person/**} was protected at all
 * depended entirely on what the surrounding application happened to configure.
 *
 * <p>The token decoder here plays the part of the <em>application's</em> {@code JwtDecoder} -
 * including rejecting a token the application considers to have the wrong audience. That is the
 * point of reusing the application's decoder instead of building one from Boot properties.
 */
@SpringBootTest(
    classes = PersonApiSecurityChainTest.TestApp.class,
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = {
        "orgsec.api.person.enabled=true",
        "orgsec.api.person.required-role=ORGSEC_API_CLIENT",
        "spring.autoconfigure.exclude=com.nomendi6.orgsec.storage.inmemory.StorageConfiguration"
    }
)
@AutoConfigureMockMvc
class PersonApiSecurityChainTest {

    private static final String PERSON_BY_ID = "/api/orgsec/person/1";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ApplicationContext context;

    @Test
    void shouldRegisterTheChainUnderTheDocumentedBeanName() {
        assertThat(context.containsBean("orgsecApiSecurityFilterChain")).isTrue();
    }

    @Test
    void shouldRejectAnonymousCallerWith401() throws Exception {
        mockMvc.perform(get(PERSON_BY_ID))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("CALLBACK_UNAUTHENTICATED"));
    }

    @Test
    void shouldRejectGarbageTokenWith401() throws Exception {
        bearer("not-a-token")
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("CALLBACK_UNAUTHENTICATED"));
    }

    @Test
    void shouldRejectTokenTheApplicationDecoderRefusesWith401() throws Exception {
        // Stands in for a JHipster-style AudienceValidator wired into the application's decoder.
        bearer("wrong-audience")
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("CALLBACK_UNAUTHENTICATED"));
    }

    @Test
    void shouldRejectAuthenticatedCallerWithoutTheRequiredRoleWith403() throws Exception {
        bearer("without-role")
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("CALLBACK_FORBIDDEN"));
    }

    @Test
    void shouldAllowServiceAccountHoldingTheRequiredRealmRole() throws Exception {
        bearer("with-role").andExpect(status().isOk());
    }

    @Test
    void shouldAnswerFromTheDelegateStorageNotFromThePrimaryOne() throws Exception {
        // In JWT mode the @Primary storage resolves the person from the token of the request in
        // flight - here, the mapper's own service-account token. The Person API must answer from
        // the database instead, or the mapper gets its own identity back for every user.
        bearer("with-role").andExpect(status().isOk())
            .andExpect(jsonPath("$.version").value("1.0"))
            .andExpect(jsonPath("$.name").value("inmemory-delegate"));

        SecurityDataStorage bound = (SecurityDataStorage) ReflectionTestUtils.getField(
            context.getBean(PersonApiService.class),
            "securityDataStorage"
        );
        assertThat(bound).isSameAs(context.getBean("delegateSecurityDataStorage", SecurityDataStorage.class));
    }

    @Test
    void shouldBindThePathVariableOnTheEndpointTheKeycloakMapperActuallyCalls() throws Exception {
        // Regression: the starter is published as a jar, and up to 1.0.4 it was compiled without
        // -parameters. @PathVariable had no name to bind to, so every call to the mapper endpoint
        // came back 500 regardless of authentication. 404 here means the name resolved and the
        // request reached the service.
        mockMvc
            .perform(get("/api/orgsec/person/by-user/8f2b1c34-0000-4000-8000-000000000001")
                .header(HttpHeaders.AUTHORIZATION, "Bearer with-role"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("PERSON_NOT_FOUND"));
    }

    private ResultActions bearer(String token) throws Exception {
        return mockMvc.perform(get(PERSON_BY_ID).header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
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
        PersonLoader personLoader(AllRolesStore rolesStore, AllPersonsStore personsStore) {
            return new PersonLoader(rolesStore, personsStore);
        }

        @Bean
        SecurityQueryProvider securityQueryProvider() {
            SecurityQueryProvider provider = mock(SecurityQueryProvider.class);
            when(provider.loadPersonByUserId(anyString())).thenReturn(List.of());
            return provider;
        }

        @Bean
        @Primary
        SecurityDataStorage jwtSecurityDataStorage() {
            return new StubSecurityDataStorage("jwt-primary");
        }

        @Bean("delegateSecurityDataStorage")
        SecurityDataStorage delegateSecurityDataStorage() {
            return new StubSecurityDataStorage("inmemory-delegate");
        }

        /**
         * The application's decoder: the only place issuer and audience are checked.
         */
        @Bean
        JwtDecoder jwtDecoder() {
            return token -> {
                switch (token) {
                    case "with-role":
                        return jwt(token, List.of("ORGSEC_API_CLIENT", "offline_access"));
                    case "without-role":
                        return jwt(token, List.of("offline_access"));
                    case "wrong-audience":
                        throw new JwtValidationException(
                            "The aud claim is not valid",
                            List.of(new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN, "The aud claim is not valid", null))
                        );
                    default:
                        throw new BadJwtException("Malformed token");
                }
            };
        }

        private static Jwt jwt(String tokenValue, List<String> realmRoles) {
            Instant now = Instant.now();
            return Jwt.withTokenValue(tokenValue)
                .header("alg", "none")
                .subject("service-account-orgsec-mapper")
                .claim("realm_access", Map.of("roles", realmRoles))
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300))
                .build();
        }
    }
}
