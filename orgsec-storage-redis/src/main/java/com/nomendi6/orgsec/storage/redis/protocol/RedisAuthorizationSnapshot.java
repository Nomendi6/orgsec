package com.nomendi6.orgsec.storage.redis.protocol;

import com.nomendi6.orgsec.model.OrganizationDef;
import com.nomendi6.orgsec.model.PersonDef;
import com.nomendi6.orgsec.model.PrivilegeDef;
import com.nomendi6.orgsec.model.RoleDef;
import com.nomendi6.orgsec.storage.redis.bootstrap.RedisSnapshotFamily;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable local authorization view decoded from one verified READY generation.
 *
 * <p>Canonical payloads stay in their wire form. Every GET re-decodes so callers cannot mutate
 * the process-wide view through the returned domain objects.</p>
 */
final class RedisAuthorizationSnapshot {

    private final RedisSnapshotGeneration generation;
    private final RedisSnapshotManifest.Verified manifest;
    private final Map<Long, byte[]> persons;
    private final Map<Long, byte[]> organizations;
    private final Map<Long, byte[]> partyRoles;
    private final Map<Long, byte[]> positionRoles;
    private final Map<Long, byte[]> roles;
    private final Map<String, byte[]> privileges;
    private final RedisCanonicalSnapshotPayloadCodec codec;

    RedisAuthorizationSnapshot(
        RedisVerifiedSnapshotView verified,
        Map<RedisSnapshotFamily, Map<byte[], byte[]>> familyPayloads
    ) {
        this(
            verified,
            familyPayloads,
            new RedisCanonicalSnapshotPayloadCodec()
        );
    }

    RedisAuthorizationSnapshot(
        RedisVerifiedSnapshotView verified,
        Map<RedisSnapshotFamily, Map<byte[], byte[]>> familyPayloads,
        RedisCanonicalSnapshotPayloadCodec codec
    ) {
        Objects.requireNonNull(verified, "verified must not be null");
        Objects.requireNonNull(familyPayloads, "familyPayloads must not be null");
        this.generation = verified.generation();
        this.manifest = verified.manifest();
        this.codec = Objects.requireNonNull(codec, "codec must not be null");
        this.persons = longKeyed(familyPayloads.get(RedisSnapshotFamily.PERSONS));
        this.organizations = longKeyed(familyPayloads.get(RedisSnapshotFamily.ORGANIZATIONS));
        this.partyRoles = longKeyed(familyPayloads.get(RedisSnapshotFamily.PARTY_ROLES));
        this.positionRoles = longKeyed(familyPayloads.get(RedisSnapshotFamily.POSITION_ROLES));
        this.roles = longKeyed(familyPayloads.get(RedisSnapshotFamily.ROLES));
        this.privileges = stringKeyed(familyPayloads.get(RedisSnapshotFamily.PRIVILEGES));
    }

    RedisSnapshotGeneration generation() {
        return generation;
    }

    RedisSnapshotManifest.Verified manifest() {
        return manifest;
    }

    PersonDef person(Long personId) {
        return decodeLong(persons, personId, codec::decodePerson);
    }

    OrganizationDef organization(Long organizationId) {
        return decodeLong(organizations, organizationId, codec::decodeOrganization);
    }

    RoleDef partyRole(Long roleId) {
        return decodeLong(partyRoles, roleId, codec::decodeRole);
    }

    RoleDef positionRole(Long roleId) {
        return decodeLong(positionRoles, roleId, codec::decodeRole);
    }

    RoleDef role(Long roleId) {
        return decodeLong(roles, roleId, codec::decodeRole);
    }

    PrivilegeDef privilege(String name) {
        if (name == null) {
            return null;
        }
        byte[] payload = privileges.get(name);
        return payload == null ? null : codec.decode(payload);
    }

    Map<Long, PersonDef> persons(Iterable<Long> personIds) {
        Map<Long, PersonDef> result = new LinkedHashMap<>();
        if (personIds == null) {
            return Map.of();
        }
        for (Long personId : personIds) {
            PersonDef person = person(personId);
            if (person != null) {
                result.put(personId, person);
            }
        }
        return Collections.unmodifiableMap(result);
    }

    Map<Long, OrganizationDef> organizations(Iterable<Long> organizationIds) {
        Map<Long, OrganizationDef> result = new LinkedHashMap<>();
        if (organizationIds == null) {
            return Map.of();
        }
        for (Long organizationId : organizationIds) {
            OrganizationDef organization = organization(organizationId);
            if (organization != null) {
                result.put(organizationId, organization);
            }
        }
        return Collections.unmodifiableMap(result);
    }

    private static Map<Long, byte[]> longKeyed(Map<byte[], byte[]> raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        Map<Long, byte[]> mapped = new LinkedHashMap<>();
        for (Map.Entry<byte[], byte[]> entry : raw.entrySet()) {
            mapped.put(
                Long.parseLong(new String(entry.getKey(), StandardCharsets.US_ASCII)),
                entry.getValue().clone()
            );
        }
        return Collections.unmodifiableMap(mapped);
    }

    private static Map<String, byte[]> stringKeyed(Map<byte[], byte[]> raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        Map<String, byte[]> mapped = new LinkedHashMap<>();
        for (Map.Entry<byte[], byte[]> entry : raw.entrySet()) {
            mapped.put(
                new String(entry.getKey(), StandardCharsets.UTF_8),
                entry.getValue().clone()
            );
        }
        return Collections.unmodifiableMap(mapped);
    }

    private static <T> T decodeLong(
        Map<Long, byte[]> values,
        Long id,
        java.util.function.Function<byte[], T> decoder
    ) {
        if (id == null) {
            return null;
        }
        byte[] payload = values.get(id);
        return payload == null ? null : decoder.apply(payload);
    }
}
