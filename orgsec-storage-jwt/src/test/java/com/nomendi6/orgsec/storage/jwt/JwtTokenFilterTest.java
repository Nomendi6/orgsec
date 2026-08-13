package com.nomendi6.orgsec.storage.jwt;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JwtTokenFilterTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldExtractBearerTokenAndClearThreadLocalAfterRequest() throws Exception {
        JwtTokenContextHolder contextHolder = new JwtTokenContextHolder();
        JwtTokenFilter filter = new JwtTokenFilter(contextHolder);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer bearer-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (servletRequest, servletResponse) ->
            assertThat(contextHolder.getToken()).isEqualTo("bearer-token");

        filter.doFilter(request, response, chain);

        assertThat(contextHolder.getToken()).isNull();
    }

    @Test
    void shouldExtractOidcIdTokenWhenAuthorizationHeaderIsMissing() throws Exception {
        JwtTokenContextHolder contextHolder = new JwtTokenContextHolder();
        JwtTokenFilter filter = new JwtTokenFilter(contextHolder);
        OidcIdToken idToken = new OidcIdToken(
            "oidc-id-token",
            Instant.now(),
            Instant.now().plusSeconds(60),
            Map.of("sub", "alice")
        );
        DefaultOidcUser principal = new DefaultOidcUser(List.of(), idToken);
        OAuth2AuthenticationToken authentication = new OAuth2AuthenticationToken(principal, List.of(), "keycloak");
        SecurityContextHolder.getContext().setAuthentication(authentication);
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (servletRequest, servletResponse) ->
            assertThat(contextHolder.getToken()).isEqualTo("oidc-id-token");

        filter.doFilter(request, response, chain);
    }
}
