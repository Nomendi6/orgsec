package com.nomendi6.orgsec.autoconfigure;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

/**
 * Maps Keycloak's {@code realm_access.roles} claim onto {@code ROLE_*} authorities.
 *
 * <p>Spring Security's default converter reads {@code scope}/{@code scp} and prefixes with
 * {@code SCOPE_}, which never matches the {@code hasRole(...)} check on the Person API chain.
 * The mapper's service account carries its permission as a realm role, so that is what is read
 * here.
 *
 * <p>Package-private on purpose: this is wiring for {@link PersonApiServiceConfiguration}, not
 * public API. It is only loaded when {@code spring-security-oauth2-jose} is on the classpath.
 */
final class KeycloakRealmRoleConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    private static final String REALM_ACCESS_CLAIM = "realm_access";
    private static final String ROLES_KEY = "roles";
    private static final String ROLE_PREFIX = "ROLE_";

    static JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new KeycloakRealmRoleConverter());
        return converter;
    }

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        Object realmAccess = jwt.getClaim(REALM_ACCESS_CLAIM);
        if (!(realmAccess instanceof Map<?, ?> realmAccessMap)) {
            return List.of();
        }
        Object roles = realmAccessMap.get(ROLES_KEY);
        if (!(roles instanceof Collection<?> roleValues)) {
            return List.of();
        }

        List<GrantedAuthority> authorities = new ArrayList<>(roleValues.size());
        for (Object role : roleValues) {
            if (role instanceof String roleName && !roleName.isBlank()) {
                authorities.add(new SimpleGrantedAuthority(ROLE_PREFIX + roleName));
            }
        }
        return authorities;
    }
}
