package com.nomendi6.orgsec.storage.redis.protocol;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.StreamWriteConstraints;
import com.fasterxml.jackson.core.json.JsonWriteFeature;
import com.nomendi6.orgsec.constants.PrivilegeDirection;
import com.nomendi6.orgsec.constants.PrivilegeOperation;
import com.nomendi6.orgsec.model.BusinessRoleDef;
import com.nomendi6.orgsec.model.OrganizationDef;
import com.nomendi6.orgsec.model.PersonDef;
import com.nomendi6.orgsec.model.PrivilegeDef;
import com.nomendi6.orgsec.model.ResourceDef;
import com.nomendi6.orgsec.model.RoleDef;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Canonical bounded JSON codec for snapshot payload values.
 *
 * <p>The codec owns one locked Jackson factory and exposes domain values and byte arrays only.
 * Jackson parser and generator objects never cross this class boundary. Every object schema and
 * field order is written and read explicitly; automatic field discovery, application mapper
 * settings, runtime subtype metadata, and locale-dependent ordering are not part of the wire
 * format.</p>
 *
 * <p>Unicode code points are preserved exactly without NFC/NFD normalization. Unordered string
 * collections and resource-map keys use unsigned lexicographic order of their exact UTF-8 bytes.
 * The privilege list remains ordered because {@link ResourceDef#getPrivilegesList()} is a list,
 * not a set.</p>
 */
final class RedisCanonicalSnapshotPayloadCodec {

    /** Existing standalone privilege bound retained as a wire invariant. */
    static final int MAX_PRIVILEGE_PAYLOAD_BYTES = 4096;

    /** Bounded complete RoleDef -> ResourceDef -> PrivilegeDef graph. */
    static final int MAX_ROLE_PAYLOAD_BYTES = 1024 * 1024;

    /** Bounded complete OrganizationDef graph, including roles and business roles. */
    static final int MAX_ORGANIZATION_PAYLOAD_BYTES = 2 * 1024 * 1024;

    /** Bounded complete PersonDef graph, including all embedded organizations. */
    static final int MAX_PERSON_PAYLOAD_BYTES = 4 * 1024 * 1024;

    /** Compatibility alias for the original privilege-only codec tests and callers. */
    static final int MAX_PAYLOAD_BYTES = MAX_PRIVILEGE_PAYLOAD_BYTES;

    static final int MAX_STRING_UTF16_UNITS = 255;
    static final int MAX_STRING_UTF8_BYTES = MAX_STRING_UTF16_UNITS * 3;
    static final int MAX_PATH_UTF16_UNITS = 1000;
    static final int MAX_PATH_UTF8_BYTES = MAX_PATH_UTF16_UNITS * 3;
    /** Explicit wire cap for the otherwise unbounded BusinessRoleDef.filter field. */
    static final int MAX_FILTER_UTF16_UNITS = 65_536;
    static final int MAX_FILTER_UTF8_BYTES = 65_536;
    static final int MAX_COLLECTION_ENTRIES = 4096;
    static final int MAX_NESTING_DEPTH = 16;

    private static final int MAX_NUMBER_CHARACTERS = 20;
    /** Scaled with the 4 MiB graph bound; still finite but above every canonical schema shape. */
    private static final long MAX_JSON_TOKENS = 800_000;

    private static final String FIELD_NAME = "name";
    private static final String FIELD_RESOURCE_NAME = "resourceName";
    private static final String FIELD_OPERATION = "operation";
    private static final String FIELD_ALL = "all";
    private static final String FIELD_COMPANY = "company";
    private static final String FIELD_ORG = "org";
    private static final String FIELD_PERSON = "person";

    private static final String FIELD_ROLE_ID = "roleId";
    private static final String FIELD_SECURITY_PRIVILEGE_SET = "securityPrivilegeSet";
    private static final String FIELD_RESOURCES_MAP = "resourcesMap";
    private static final String FIELD_BUSINESS_ROLES = "businessRoles";

    private static final String FIELD_ORGANIZATION_ID = "organizationId";
    private static final String FIELD_ORGANIZATION_NAME = "organizationName";
    private static final String FIELD_POSITION_ID = "positionId";
    private static final String FIELD_PATH_ID = "pathId";
    private static final String FIELD_PARENT_PATH = "parentPath";
    private static final String FIELD_COMPANY_ID = "companyId";
    private static final String FIELD_COMPANY_PARENT_PATH = "companyParentPath";
    private static final String FIELD_PARENT_ID = "parentId";
    private static final String FIELD_ORG_LINEAGE_IDS = "orgLineageIds";
    private static final String FIELD_COMPANY_LINEAGE_IDS = "companyLineageIds";
    private static final String FIELD_POSITION_ROLES_SET = "positionRolesSet";
    private static final String FIELD_ORGANIZATION_ROLES_SET = "organizationRolesSet";
    private static final String FIELD_BUSINESS_ROLES_MAP = "businessRolesMap";

    private static final String FIELD_BUSINESS_ROLE_NAME = "businessRoleName";
    private static final String FIELD_FILTER = "filter";
    private static final String FIELD_ALLOW_ALL = "allowAll";

    private static final String FIELD_PERSON_ID = "personId";
    private static final String FIELD_PERSON_NAME = "personName";
    private static final String FIELD_DEFAULT_COMPANY_ID = "defaultCompanyId";
    private static final String FIELD_DEFAULT_ORGUNIT_ID = "defaultOrgunitId";
    private static final String FIELD_RELATED_USER_ID = "relatedUserId";
    private static final String FIELD_RELATED_USER_LOGIN = "relatedUserLogin";
    private static final String FIELD_ORGANIZATIONS_MAP = "organizationsMap";

    private static final String FIELD_KEY = "key";
    private static final String FIELD_VALUE = "value";
    private static final String FIELD_PRIVILEGES_LIST = "privilegesList";
    private static final String FIELD_AGGREGATED_WRITE_PRIVILEGE =
        "aggregatedWritePrivilege";
    private static final String FIELD_AGGREGATED_READ_PRIVILEGE =
        "aggregatedReadPrivilege";
    private static final String FIELD_AGGREGATED_EXECUTE_PRIVILEGE =
        "aggregatedExecutePrivilege";

    private static final Set<String> PRIVILEGE_FIELDS = Set.of(
        FIELD_NAME,
        FIELD_RESOURCE_NAME,
        FIELD_OPERATION,
        FIELD_ALL,
        FIELD_COMPANY,
        FIELD_ORG,
        FIELD_PERSON
    );
    private static final Set<String> ROLE_FIELDS = Set.of(
        FIELD_ROLE_ID,
        FIELD_NAME,
        FIELD_SECURITY_PRIVILEGE_SET,
        FIELD_RESOURCES_MAP,
        FIELD_BUSINESS_ROLES
    );
    private static final Set<String> RESOURCE_ENTRY_FIELDS = Set.of(FIELD_KEY, FIELD_VALUE);
    private static final Set<String> RESOURCE_FIELDS = Set.of(
        FIELD_RESOURCE_NAME,
        FIELD_PRIVILEGES_LIST,
        FIELD_AGGREGATED_WRITE_PRIVILEGE,
        FIELD_AGGREGATED_READ_PRIVILEGE,
        FIELD_AGGREGATED_EXECUTE_PRIVILEGE
    );
    private static final Set<String> ORGANIZATION_FIELDS = Set.of(
        FIELD_ORGANIZATION_ID,
        FIELD_ORGANIZATION_NAME,
        FIELD_POSITION_ID,
        FIELD_PATH_ID,
        FIELD_PARENT_PATH,
        FIELD_COMPANY_ID,
        FIELD_COMPANY_PARENT_PATH,
        FIELD_PARENT_ID,
        FIELD_ORG_LINEAGE_IDS,
        FIELD_COMPANY_LINEAGE_IDS,
        FIELD_POSITION_ROLES_SET,
        FIELD_ORGANIZATION_ROLES_SET,
        FIELD_BUSINESS_ROLES_MAP
    );
    private static final Set<String> BUSINESS_ROLE_FIELDS = Set.of(
        FIELD_BUSINESS_ROLE_NAME,
        FIELD_RESOURCES_MAP,
        FIELD_FILTER,
        FIELD_ALLOW_ALL
    );
    private static final Set<String> PERSON_FIELDS = Set.of(
        FIELD_PERSON_ID,
        FIELD_PERSON_NAME,
        FIELD_DEFAULT_COMPANY_ID,
        FIELD_DEFAULT_ORGUNIT_ID,
        FIELD_RELATED_USER_ID,
        FIELD_RELATED_USER_LOGIN,
        FIELD_ORGANIZATIONS_MAP
    );
    private static final Set<String> MAP_ENTRY_FIELDS = Set.of(FIELD_KEY, FIELD_VALUE);

    private static final JsonFactory JSON_FACTORY = JsonFactory.builder()
        .streamReadConstraints(StreamReadConstraints.builder()
            .maxNestingDepth(MAX_NESTING_DEPTH)
            .maxDocumentLength(MAX_PERSON_PAYLOAD_BYTES)
            .maxTokenCount(MAX_JSON_TOKENS)
            .maxNumberLength(MAX_NUMBER_CHARACTERS)
            .maxStringLength(MAX_FILTER_UTF16_UNITS)
            .maxNameLength(64)
            .build())
        .streamWriteConstraints(StreamWriteConstraints.builder()
            .maxNestingDepth(MAX_NESTING_DEPTH)
            .build())
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(JsonWriteFeature.QUOTE_FIELD_NAMES)
        .enable(JsonWriteFeature.WRITE_HEX_UPPER_CASE)
        .enable(JsonWriteFeature.COMBINE_UNICODE_SURROGATES_IN_UTF8)
        .disable(JsonWriteFeature.ESCAPE_NON_ASCII)
        .disable(JsonWriteFeature.ESCAPE_FORWARD_SLASHES)
        .build();

    /**
     * Encodes the exact published privilege shape to canonical UTF-8 JSON.
     *
     * @param privilege exact {@link PrivilegeDef}, not a subtype
     * @return canonical payload bytes
     */
    byte[] encode(PrivilegeDef privilege) {
        return encodePayload(
            PayloadKind.PRIVILEGE,
            256,
            generator -> writePrivilegeObject(generator, privilege)
        );
    }

    /**
     * Decodes only exact canonical standalone privilege payload bytes.
     *
     * @param payload bounded canonical UTF-8 JSON
     * @return exact base {@link PrivilegeDef}
     * @throws CorruptPrivilegePayloadException for malformed or non-canonical input
     */
    PrivilegeDef decode(byte[] payload) {
        requireBoundedInput(payload, PayloadKind.PRIVILEGE);
        PrivilegeDef privilege;
        try (JsonParser parser = JSON_FACTORY.createParser(payload)) {
            requireToken(parser.nextToken(), JsonToken.START_OBJECT, "root object");
            privilege = readPrivilegeObject(parser);
            requireEndOfDocument(parser);
        } catch (SchemaViolationException exception) {
            throw corrupt(PayloadKind.PRIVILEGE, exception.getMessage());
        } catch (IOException | RuntimeException exception) {
            throw corrupt(PayloadKind.PRIVILEGE, "payload is not bounded strict JSON");
        }
        requireExactReencoding(payload, encodeDecodedPrivilege(privilege), PayloadKind.PRIVILEGE);
        return privilege;
    }

    /**
     * Encodes a family-independent RoleDef payload with its complete ResourceDef graph.
     *
     * <p>Party, position, and legacy role domain separation belongs to the entry/family digest
     * frame. The payload itself is deliberately identical for the same RoleDef value.</p>
     *
     * @param role exact {@link RoleDef}, not a subtype
     * @return canonical role payload bytes
     */
    byte[] encode(RoleDef role) {
        return encodePayload(
            PayloadKind.ROLE,
            1024,
            generator -> writeRoleObject(generator, role)
        );
    }

    /**
     * Decodes only exact canonical RoleDef payload bytes.
     *
     * @param payload bounded canonical UTF-8 JSON
     * @return exact base {@link RoleDef} with exact base nested values
     * @throws CorruptRolePayloadException for malformed or non-canonical input
     */
    RoleDef decodeRole(byte[] payload) {
        requireBoundedInput(payload, PayloadKind.ROLE);
        RoleDef role;
        try (JsonParser parser = JSON_FACTORY.createParser(payload)) {
            requireToken(parser.nextToken(), JsonToken.START_OBJECT, "root object");
            role = readRoleObject(parser);
            requireEndOfDocument(parser);
        } catch (SchemaViolationException exception) {
            throw corrupt(PayloadKind.ROLE, exception.getMessage());
        } catch (IOException | RuntimeException exception) {
            throw corrupt(PayloadKind.ROLE, "payload is not bounded strict JSON");
        }
        requireExactReencoding(payload, encodeDecodedRole(role), PayloadKind.ROLE);
        return role;
    }

    /**
     * Encodes an exact OrganizationDef and its complete authorization graph.
     *
     * @param organization exact {@link OrganizationDef}, not a subtype
     * @return canonical organization payload bytes
     */
    byte[] encode(OrganizationDef organization) {
        return encodePayload(
            PayloadKind.ORGANIZATION,
            4096,
            generator -> writeOrganizationObject(generator, organization)
        );
    }

    /**
     * Decodes only exact canonical OrganizationDef payload bytes.
     *
     * @param payload bounded canonical UTF-8 JSON
     * @return exact base OrganizationDef graph
     * @throws CorruptOrganizationPayloadException for malformed or non-canonical input
     */
    OrganizationDef decodeOrganization(byte[] payload) {
        requireBoundedInput(payload, PayloadKind.ORGANIZATION);
        OrganizationDef organization;
        try (JsonParser parser = JSON_FACTORY.createParser(payload)) {
            requireToken(parser.nextToken(), JsonToken.START_OBJECT, "root object");
            organization = readOrganizationObject(parser);
            requireEndOfDocument(parser);
        } catch (SchemaViolationException exception) {
            throw corrupt(PayloadKind.ORGANIZATION, exception.getMessage());
        } catch (IOException | RuntimeException exception) {
            throw corrupt(PayloadKind.ORGANIZATION, "payload is not bounded strict JSON");
        }
        requireExactReencoding(
            payload,
            encodeDecodedOrganization(organization),
            PayloadKind.ORGANIZATION
        );
        return organization;
    }

    /**
     * Encodes an exact PersonDef and its complete embedded organization graph.
     *
     * @param person exact {@link PersonDef}, not a subtype
     * @return canonical person payload bytes
     */
    byte[] encode(PersonDef person) {
        return encodePayload(
            PayloadKind.PERSON,
            8192,
            generator -> writePersonObject(generator, person)
        );
    }

    /**
     * Decodes only exact canonical PersonDef payload bytes.
     *
     * @param payload bounded canonical UTF-8 JSON
     * @return exact base PersonDef graph
     * @throws CorruptPersonPayloadException for malformed or non-canonical input
     */
    PersonDef decodePerson(byte[] payload) {
        requireBoundedInput(payload, PayloadKind.PERSON);
        PersonDef person;
        try (JsonParser parser = JSON_FACTORY.createParser(payload)) {
            requireToken(parser.nextToken(), JsonToken.START_OBJECT, "root object");
            person = readPersonObject(parser);
            requireEndOfDocument(parser);
        } catch (SchemaViolationException exception) {
            throw corrupt(PayloadKind.PERSON, exception.getMessage());
        } catch (IOException | RuntimeException exception) {
            throw corrupt(PayloadKind.PERSON, "payload is not bounded strict JSON");
        }
        requireExactReencoding(payload, encodeDecodedPerson(person), PayloadKind.PERSON);
        return person;
    }

    private static byte[] encodePayload(
        PayloadKind kind,
        int initialCapacity,
        PayloadWriter writer
    ) {
        HardBoundedByteArrayOutputStream output = new HardBoundedByteArrayOutputStream(
            initialCapacity,
            kind.maxPayloadBytes
        );
        try {
            try (JsonGenerator generator = JSON_FACTORY.createGenerator(output)) {
                writer.write(generator);
            }
            return output.toByteArray();
        } catch (IOException exception) {
            if (hasOutputLimitCause(exception)) {
                throw oversizedPayload(kind);
            }
            throw new IllegalStateException(
                "cannot encode canonical " + kind.label + " payload",
                exception
            );
        }
    }

    private static boolean hasOutputLimitCause(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof OutputLimitExceededException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static IllegalArgumentException oversizedPayload(PayloadKind kind) {
        return new IllegalArgumentException(
            "canonical " + kind.label + " payload exceeds "
                + kind.maxPayloadBytes + " bytes"
        );
    }

    private byte[] encodeDecodedPrivilege(PrivilegeDef privilege) {
        try {
            return encode(privilege);
        } catch (RuntimeException exception) {
            throw corrupt(
                PayloadKind.PRIVILEGE,
                "decoded privilege violates the canonical schema"
            );
        }
    }

    private byte[] encodeDecodedRole(RoleDef role) {
        try {
            return encode(role);
        } catch (RuntimeException exception) {
            throw corrupt(PayloadKind.ROLE, "decoded role violates the canonical schema");
        }
    }

    private byte[] encodeDecodedOrganization(OrganizationDef organization) {
        try {
            return encode(organization);
        } catch (RuntimeException exception) {
            throw corrupt(
                PayloadKind.ORGANIZATION,
                "decoded organization violates the canonical schema"
            );
        }
    }

    private byte[] encodeDecodedPerson(PersonDef person) {
        try {
            return encode(person);
        } catch (RuntimeException exception) {
            throw corrupt(PayloadKind.PERSON, "decoded person violates the canonical schema");
        }
    }

    private static void requireExactReencoding(
        byte[] payload,
        byte[] canonical,
        PayloadKind kind
    ) {
        if (!Arrays.equals(payload, canonical)) {
            throw corrupt(kind, "payload bytes are not the exact canonical encoding");
        }
    }

    private static void writePersonObject(JsonGenerator generator, PersonDef person)
        throws IOException {
        ValidatedPerson validated = validatePersonForEncode(person);
        generator.writeStartObject();
        generator.writeNumberField(FIELD_PERSON_ID, person.personId);
        writeNullableString(generator, FIELD_PERSON_NAME, person.personName);
        writeNullableLong(generator, FIELD_DEFAULT_COMPANY_ID, person.defaultCompanyId);
        writeNullableLong(generator, FIELD_DEFAULT_ORGUNIT_ID, person.defaultOrgunitId);
        writeNullableString(generator, FIELD_RELATED_USER_ID, person.relatedUserId);
        writeNullableString(generator, FIELD_RELATED_USER_LOGIN, person.relatedUserLogin);
        writeOrganizationsArray(generator, validated.organizations);
        generator.writeEndObject();
    }

    private static PersonDef readPersonObject(JsonParser parser) throws IOException {
        requireToken(parser.currentToken(), JsonToken.START_OBJECT, "person object");
        requireField(parser, FIELD_PERSON_ID, PERSON_FIELDS);
        long personId = readRequiredLong(parser, FIELD_PERSON_ID);
        requireField(parser, FIELD_PERSON_NAME, PERSON_FIELDS);
        String personName = readNullableString(parser, FIELD_PERSON_NAME);
        requireField(parser, FIELD_DEFAULT_COMPANY_ID, PERSON_FIELDS);
        Long defaultCompanyId = readNullableLong(parser, FIELD_DEFAULT_COMPANY_ID);
        requireField(parser, FIELD_DEFAULT_ORGUNIT_ID, PERSON_FIELDS);
        Long defaultOrgunitId = readNullableLong(parser, FIELD_DEFAULT_ORGUNIT_ID);
        requireField(parser, FIELD_RELATED_USER_ID, PERSON_FIELDS);
        String relatedUserId = readNullableString(parser, FIELD_RELATED_USER_ID);
        requireField(parser, FIELD_RELATED_USER_LOGIN, PERSON_FIELDS);
        String relatedUserLogin = readNullableString(parser, FIELD_RELATED_USER_LOGIN);
        requireField(parser, FIELD_ORGANIZATIONS_MAP, PERSON_FIELDS);
        Map<Long, OrganizationDef> organizations = readOrganizationsMap(parser);
        requireObjectEnd(parser, PERSON_FIELDS, "person object");

        PersonDef person = new PersonDef(personId, personName);
        person.defaultCompanyId = defaultCompanyId;
        person.defaultOrgunitId = defaultOrgunitId;
        person.relatedUserId = relatedUserId;
        person.relatedUserLogin = relatedUserLogin;
        person.organizationsMap.putAll(organizations);
        return person;
    }

    private static ValidatedPerson validatePersonForEncode(PersonDef person) {
        if (person == null) {
            throw new IllegalArgumentException("person must not be null");
        }
        if (person.getClass() != PersonDef.class) {
            throw new IllegalArgumentException("person must have the exact PersonDef class");
        }
        if (person.personId == null) {
            throw new IllegalArgumentException("personId must not be null");
        }
        validateNullableStringForEncode(FIELD_PERSON_NAME, person.personName);
        validateNullableStringForEncode(FIELD_RELATED_USER_ID, person.relatedUserId);
        validateNullableStringForEncode(FIELD_RELATED_USER_LOGIN, person.relatedUserLogin);
        return new ValidatedPerson(canonicalOrganizations(person.organizationsMap));
    }

    private static void writeOrganizationsArray(
        JsonGenerator generator,
        List<OrganizationEntry> organizations
    ) throws IOException {
        generator.writeArrayFieldStart(FIELD_ORGANIZATIONS_MAP);
        for (OrganizationEntry entry : organizations) {
            generator.writeStartObject();
            generator.writeStringField(FIELD_KEY, entry.key.value);
            generator.writeFieldName(FIELD_VALUE);
            writeOrganizationObject(generator, entry.value);
            generator.writeEndObject();
        }
        generator.writeEndArray();
    }

    private static Map<Long, OrganizationDef> readOrganizationsMap(JsonParser parser)
        throws IOException {
        requireToken(parser.nextToken(), JsonToken.START_ARRAY, FIELD_ORGANIZATIONS_MAP + " array");
        Map<Long, OrganizationDef> organizations = new LinkedHashMap<>();
        byte[] previousKey = null;
        while (parser.nextToken() != JsonToken.END_ARRAY) {
            requireToken(parser.currentToken(), JsonToken.START_OBJECT, "organization-map entry");
            ensureCollectionRoom(organizations.size(), FIELD_ORGANIZATIONS_MAP);
            requireField(parser, FIELD_KEY, MAP_ENTRY_FIELDS);
            String keyToken = readRequiredString(parser, FIELD_KEY, true);
            long key = parseCanonicalLongKey(FIELD_ORGANIZATIONS_MAP, keyToken);
            byte[] keyBytes = strictUtf8ForDecode(FIELD_KEY, keyToken);
            requireCanonicalMapOrder(previousKey, keyBytes, FIELD_ORGANIZATIONS_MAP);
            requireField(parser, FIELD_VALUE, MAP_ENTRY_FIELDS);
            requireToken(parser.nextToken(), JsonToken.START_OBJECT, "organization value object");
            OrganizationDef organization = readOrganizationObject(parser);
            requireObjectEnd(parser, MAP_ENTRY_FIELDS, "organization-map entry");
            if (organization.organizationId.longValue() != key) {
                throw violation("organization-map key must equal OrganizationDef.organizationId");
            }
            if (organizations.putIfAbsent(key, organization) != null) {
                throw violation("organization-map keys must be unique");
            }
            previousKey = keyBytes;
        }
        return organizations;
    }

    private static List<OrganizationEntry> canonicalOrganizations(
        Map<Long, OrganizationDef> organizations
    ) {
        if (organizations == null) {
            throw new IllegalArgumentException(FIELD_ORGANIZATIONS_MAP + " must not be null");
        }
        List<OrganizationEntry> canonical = new ArrayList<>();
        for (Map.Entry<Long, OrganizationDef> entry : organizations.entrySet()) {
            ensureCollectionRoomForEncode(canonical.size(), FIELD_ORGANIZATIONS_MAP);
            Long key = entry.getKey();
            if (key == null) {
                throw new IllegalArgumentException(
                    FIELD_ORGANIZATIONS_MAP + " must not contain null keys"
                );
            }
            OrganizationDef value = entry.getValue();
            validateOrganizationIdentityForEncode(value);
            if (!key.equals(value.organizationId)) {
                throw new IllegalArgumentException(
                    "organization-map key must equal OrganizationDef.organizationId"
                );
            }
            canonical.add(new OrganizationEntry(canonicalLongKey(key), value));
        }
        canonical.sort(OrganizationEntry::compareTo);
        requireUniqueCanonicalEntries(canonical, FIELD_ORGANIZATIONS_MAP);
        return List.copyOf(canonical);
    }

    private static void writeOrganizationObject(
        JsonGenerator generator,
        OrganizationDef organization
    ) throws IOException {
        ValidatedOrganization validated = validateOrganizationForEncode(organization);
        generator.writeStartObject();
        generator.writeNumberField(FIELD_ORGANIZATION_ID, organization.organizationId);
        writeNullableString(generator, FIELD_ORGANIZATION_NAME, organization.organizationName);
        writeNullableLong(generator, FIELD_POSITION_ID, organization.positionId);
        writeNullableString(generator, FIELD_PATH_ID, organization.pathId);
        writeNullableString(generator, FIELD_PARENT_PATH, organization.parentPath);
        writeNullableLong(generator, FIELD_COMPANY_ID, organization.companyId);
        writeNullableString(
            generator,
            FIELD_COMPANY_PARENT_PATH,
            organization.companyParentPath
        );
        writeRoleArray(generator, FIELD_POSITION_ROLES_SET, validated.positionRoles);
        writeRoleArray(
            generator,
            FIELD_ORGANIZATION_ROLES_SET,
            validated.organizationRoles
        );
        writeBusinessRolesArray(generator, validated.businessRoles);
        writeNullableLong(generator, FIELD_PARENT_ID, organization.parentId);
        writeNullableLongList(generator, FIELD_ORG_LINEAGE_IDS, organization.orgLineageIds);
        writeNullableLongList(generator, FIELD_COMPANY_LINEAGE_IDS, organization.companyLineageIds);
        generator.writeEndObject();
    }

    private static OrganizationDef readOrganizationObject(JsonParser parser) throws IOException {
        requireToken(parser.currentToken(), JsonToken.START_OBJECT, "organization object");
        requireField(parser, FIELD_ORGANIZATION_ID, ORGANIZATION_FIELDS);
        long organizationId = readRequiredLong(parser, FIELD_ORGANIZATION_ID);
        requireField(parser, FIELD_ORGANIZATION_NAME, ORGANIZATION_FIELDS);
        String organizationName = readNullableString(parser, FIELD_ORGANIZATION_NAME);
        requireField(parser, FIELD_POSITION_ID, ORGANIZATION_FIELDS);
        Long positionId = readNullableLong(parser, FIELD_POSITION_ID);
        requireField(parser, FIELD_PATH_ID, ORGANIZATION_FIELDS);
        String pathId = readNullablePathString(parser, FIELD_PATH_ID);
        requireField(parser, FIELD_PARENT_PATH, ORGANIZATION_FIELDS);
        String parentPath = readNullablePathString(parser, FIELD_PARENT_PATH);
        requireField(parser, FIELD_COMPANY_ID, ORGANIZATION_FIELDS);
        Long companyId = readNullableLong(parser, FIELD_COMPANY_ID);
        requireField(parser, FIELD_COMPANY_PARENT_PATH, ORGANIZATION_FIELDS);
        String companyParentPath = readNullablePathString(
            parser,
            FIELD_COMPANY_PARENT_PATH
        );
        requireField(parser, FIELD_POSITION_ROLES_SET, ORGANIZATION_FIELDS);
        Set<RoleDef> positionRoles = readCanonicalRoleSet(parser, FIELD_POSITION_ROLES_SET);
        requireField(parser, FIELD_ORGANIZATION_ROLES_SET, ORGANIZATION_FIELDS);
        Set<RoleDef> organizationRoles = readCanonicalRoleSet(
            parser,
            FIELD_ORGANIZATION_ROLES_SET
        );
        requireField(parser, FIELD_BUSINESS_ROLES_MAP, ORGANIZATION_FIELDS);
        Map<String, BusinessRoleDef> businessRoles = readBusinessRolesMap(parser);
        OrganizationLineageTail tail = readOptionalOrganizationLineageTail(parser);

        OrganizationDef organization = new OrganizationDef();
        organization.organizationId = organizationId;
        organization.organizationName = organizationName;
        organization.positionId = positionId;
        organization.pathId = pathId;
        organization.parentPath = parentPath;
        organization.companyId = companyId;
        organization.companyParentPath = companyParentPath;
        organization.parentId = tail.parentId;
        organization.orgLineageIds = tail.orgLineageIds;
        organization.companyLineageIds = tail.companyLineageIds;
        organization.positionRolesSet.addAll(positionRoles);
        organization.organizationRolesSet.addAll(organizationRoles);
        organization.businessRolesMap.putAll(businessRoles);
        return organization;
    }

    private static OrganizationLineageTail readOptionalOrganizationLineageTail(JsonParser parser)
        throws IOException {
        JsonToken token = parser.nextToken();
        if (token == JsonToken.END_OBJECT) {
            return OrganizationLineageTail.absent();
        }
        if (token != JsonToken.FIELD_NAME) {
            throw violation("expected parentId or the end of the organization object");
        }
        if (!FIELD_PARENT_ID.equals(parser.currentName())) {
            throw violation(ORGANIZATION_FIELDS.contains(parser.currentName())
                ? "field order is not canonical; expected parentId"
                : "unknown field where parentId was required");
        }
        Long parentId = readNullableLong(parser, FIELD_PARENT_ID);
        requireField(parser, FIELD_ORG_LINEAGE_IDS, ORGANIZATION_FIELDS);
        List<Long> orgLineageIds = readNullableLongList(parser, FIELD_ORG_LINEAGE_IDS);
        requireField(parser, FIELD_COMPANY_LINEAGE_IDS, ORGANIZATION_FIELDS);
        List<Long> companyLineageIds = readNullableLongList(parser, FIELD_COMPANY_LINEAGE_IDS);
        requireObjectEnd(parser, ORGANIZATION_FIELDS, "organization object");
        return new OrganizationLineageTail(parentId, orgLineageIds, companyLineageIds);
    }

    private record OrganizationLineageTail(
        Long parentId,
        List<Long> orgLineageIds,
        List<Long> companyLineageIds
    ) {
        static OrganizationLineageTail absent() {
            return new OrganizationLineageTail(null, null, null);
        }
    }

    private static ValidatedOrganization validateOrganizationForEncode(
        OrganizationDef organization
    ) {
        validateOrganizationIdentityForEncode(organization);
        validateNullableStringForEncode(
            FIELD_ORGANIZATION_NAME,
            organization.organizationName
        );
        validateNullablePathForEncode(FIELD_PATH_ID, organization.pathId);
        validateNullablePathForEncode(FIELD_PARENT_PATH, organization.parentPath);
        validateNullablePathForEncode(
            FIELD_COMPANY_PARENT_PATH,
            organization.companyParentPath
        );
        List<RoleEntry> positionRoles = canonicalRoleSet(
            FIELD_POSITION_ROLES_SET,
            organization.positionRolesSet
        );
        List<RoleEntry> organizationRoles = canonicalRoleSet(
            FIELD_ORGANIZATION_ROLES_SET,
            organization.organizationRolesSet
        );
        List<BusinessRoleEntry> businessRoles = canonicalBusinessRoles(
            organization.businessRolesMap
        );
        return new ValidatedOrganization(positionRoles, organizationRoles, businessRoles);
    }

    private static void validateOrganizationIdentityForEncode(OrganizationDef organization) {
        if (organization == null) {
            throw new IllegalArgumentException("organization must not be null");
        }
        if (organization.getClass() != OrganizationDef.class) {
            throw new IllegalArgumentException(
                "organization must have the exact OrganizationDef class"
            );
        }
        if (organization.organizationId == null) {
            throw new IllegalArgumentException("organizationId must not be null");
        }
    }

    private static void writeRoleArray(
        JsonGenerator generator,
        String field,
        List<RoleEntry> roles
    ) throws IOException {
        generator.writeArrayFieldStart(field);
        for (RoleEntry role : roles) {
            writeRoleObject(generator, role.value);
        }
        generator.writeEndArray();
    }

    private static Set<RoleDef> readCanonicalRoleSet(JsonParser parser, String field)
        throws IOException {
        requireToken(parser.nextToken(), JsonToken.START_ARRAY, field + " array");
        Set<RoleDef> roles = new LinkedHashSet<>();
        byte[] previousRoleId = null;
        while (parser.nextToken() != JsonToken.END_ARRAY) {
            ensureCollectionRoom(roles.size(), field);
            requireToken(parser.currentToken(), JsonToken.START_OBJECT, field + " role object");
            RoleDef role = readRoleObject(parser);
            byte[] roleId = canonicalLongKey(role.roleId).utf8;
            if (previousRoleId != null && Arrays.compareUnsigned(previousRoleId, roleId) >= 0) {
                throw violation(
                    field + " roleIds must be unique and in canonical unsigned UTF-8 order"
                );
            }
            if (!roles.add(role)) {
                throw violation(field + " must not contain duplicate roles");
            }
            previousRoleId = roleId;
        }
        return roles;
    }

    private static List<RoleEntry> canonicalRoleSet(String field, Set<RoleDef> roles) {
        if (roles == null) {
            throw new IllegalArgumentException(field + " must not be null");
        }
        List<RoleEntry> canonical = new ArrayList<>();
        for (RoleDef role : roles) {
            ensureCollectionRoomForEncode(canonical.size(), field);
            validateRoleIdentityForEncode(role);
            canonical.add(new RoleEntry(canonicalLongKey(role.roleId), role));
        }
        canonical.sort(RoleEntry::compareTo);
        requireUniqueCanonicalEntries(canonical, field + " roleIds");
        return List.copyOf(canonical);
    }

    private static void validateRoleIdentityForEncode(RoleDef role) {
        if (role == null) {
            throw new IllegalArgumentException("role must not be null");
        }
        if (role.getClass() != RoleDef.class) {
            throw new IllegalArgumentException("role must have the exact RoleDef class");
        }
        if (role.roleId == null) {
            throw new IllegalArgumentException("roleId must not be null");
        }
    }

    private static void writeBusinessRolesArray(
        JsonGenerator generator,
        List<BusinessRoleEntry> businessRoles
    ) throws IOException {
        generator.writeArrayFieldStart(FIELD_BUSINESS_ROLES_MAP);
        for (BusinessRoleEntry entry : businessRoles) {
            generator.writeStartObject();
            generator.writeStringField(FIELD_KEY, entry.key.value);
            generator.writeFieldName(FIELD_VALUE);
            writeBusinessRoleObject(generator, entry.value);
            generator.writeEndObject();
        }
        generator.writeEndArray();
    }

    private static Map<String, BusinessRoleDef> readBusinessRolesMap(JsonParser parser)
        throws IOException {
        requireToken(parser.nextToken(), JsonToken.START_ARRAY, FIELD_BUSINESS_ROLES_MAP + " array");
        Map<String, BusinessRoleDef> businessRoles = new LinkedHashMap<>();
        byte[] previousKey = null;
        while (parser.nextToken() != JsonToken.END_ARRAY) {
            requireToken(parser.currentToken(), JsonToken.START_OBJECT, "business-role-map entry");
            ensureCollectionRoom(businessRoles.size(), FIELD_BUSINESS_ROLES_MAP);
            requireField(parser, FIELD_KEY, MAP_ENTRY_FIELDS);
            String key = readRequiredString(parser, FIELD_KEY, true);
            byte[] keyBytes = strictUtf8ForDecode(FIELD_KEY, key);
            requireCanonicalMapOrder(previousKey, keyBytes, FIELD_BUSINESS_ROLES_MAP);
            requireField(parser, FIELD_VALUE, MAP_ENTRY_FIELDS);
            requireToken(parser.nextToken(), JsonToken.START_OBJECT, "business-role value object");
            BusinessRoleDef businessRole = readBusinessRoleObject(parser);
            requireObjectEnd(parser, MAP_ENTRY_FIELDS, "business-role-map entry");
            if (!key.equals(businessRole.businessRoleName)) {
                throw violation(
                    "business-role-map key must equal BusinessRoleDef.businessRoleName"
                );
            }
            if (businessRoles.putIfAbsent(key, businessRole) != null) {
                throw violation("business-role-map keys must be unique");
            }
            previousKey = keyBytes;
        }
        return businessRoles;
    }

    private static List<BusinessRoleEntry> canonicalBusinessRoles(
        Map<String, BusinessRoleDef> businessRoles
    ) {
        if (businessRoles == null) {
            throw new IllegalArgumentException(FIELD_BUSINESS_ROLES_MAP + " must not be null");
        }
        List<BusinessRoleEntry> canonical = new ArrayList<>();
        for (Map.Entry<String, BusinessRoleDef> entry : businessRoles.entrySet()) {
            ensureCollectionRoomForEncode(canonical.size(), FIELD_BUSINESS_ROLES_MAP);
            String key = entry.getKey();
            byte[] keyBytes = requiredStringUtf8ForEncode(FIELD_KEY, key, true);
            BusinessRoleDef value = entry.getValue();
            validateBusinessRoleIdentityForEncode(value);
            if (!key.equals(value.businessRoleName)) {
                throw new IllegalArgumentException(
                    "business-role-map key must equal BusinessRoleDef.businessRoleName"
                );
            }
            canonical.add(new BusinessRoleEntry(new CanonicalString(key, keyBytes), value));
        }
        canonical.sort(BusinessRoleEntry::compareTo);
        requireUniqueCanonicalEntries(canonical, FIELD_BUSINESS_ROLES_MAP);
        return List.copyOf(canonical);
    }

    private static void writeBusinessRoleObject(
        JsonGenerator generator,
        BusinessRoleDef businessRole
    ) throws IOException {
        validateBusinessRoleIdentityForEncode(businessRole);
        validateNullableFilterForEncode(businessRole.filter);
        List<ResourceEntry> resources = canonicalResources(businessRole.resourcesMap);
        generator.writeStartObject();
        generator.writeStringField(FIELD_BUSINESS_ROLE_NAME, businessRole.businessRoleName);
        writeResourcesArray(generator, resources);
        writeNullableString(generator, FIELD_FILTER, businessRole.filter);
        generator.writeBooleanField(FIELD_ALLOW_ALL, businessRole.allowAll);
        generator.writeEndObject();
    }

    private static BusinessRoleDef readBusinessRoleObject(JsonParser parser) throws IOException {
        requireToken(parser.currentToken(), JsonToken.START_OBJECT, "business-role object");
        requireField(parser, FIELD_BUSINESS_ROLE_NAME, BUSINESS_ROLE_FIELDS);
        String businessRoleName = readRequiredString(
            parser,
            FIELD_BUSINESS_ROLE_NAME,
            true
        );
        requireField(parser, FIELD_RESOURCES_MAP, BUSINESS_ROLE_FIELDS);
        Map<String, ResourceDef> resources = readResourcesMap(parser);
        requireField(parser, FIELD_FILTER, BUSINESS_ROLE_FIELDS);
        String filter = readNullableFilter(parser);
        requireField(parser, FIELD_ALLOW_ALL, BUSINESS_ROLE_FIELDS);
        boolean allowAll = readBoolean(parser, FIELD_ALLOW_ALL);
        requireObjectEnd(parser, BUSINESS_ROLE_FIELDS, "business-role object");

        BusinessRoleDef businessRole = new BusinessRoleDef(businessRoleName);
        businessRole.resourcesMap.putAll(resources);
        businessRole.filter = filter;
        businessRole.allowAll = allowAll;
        return businessRole;
    }

    private static void validateBusinessRoleIdentityForEncode(BusinessRoleDef businessRole) {
        if (businessRole == null) {
            throw new IllegalArgumentException("businessRole must not be null");
        }
        if (businessRole.getClass() != BusinessRoleDef.class) {
            throw new IllegalArgumentException(
                "businessRole must have the exact BusinessRoleDef class"
            );
        }
        requiredStringUtf8ForEncode(
            FIELD_BUSINESS_ROLE_NAME,
            businessRole.businessRoleName,
            true
        );
    }

    private static void writeRoleObject(JsonGenerator generator, RoleDef role)
        throws IOException {
        ValidatedRole validated = validateRoleForEncode(role);
        generator.writeStartObject();
        generator.writeNumberField(FIELD_ROLE_ID, role.roleId);
        writeNullableString(generator, FIELD_NAME, role.name);
        writeStringArray(generator, FIELD_SECURITY_PRIVILEGE_SET, validated.securityPrivileges);
        writeResourcesArray(generator, validated.resources);
        writeStringArray(generator, FIELD_BUSINESS_ROLES, validated.businessRoles);
        generator.writeEndObject();
    }

    private static RoleDef readRoleObject(JsonParser parser) throws IOException {
        requireToken(parser.currentToken(), JsonToken.START_OBJECT, "role object");

        requireField(parser, FIELD_ROLE_ID, ROLE_FIELDS);
        long roleId = readRequiredLong(parser, FIELD_ROLE_ID);
        requireField(parser, FIELD_NAME, ROLE_FIELDS);
        String name = readNullableString(parser, FIELD_NAME);
        requireField(parser, FIELD_SECURITY_PRIVILEGE_SET, ROLE_FIELDS);
        Set<String> securityPrivileges = readCanonicalStringSet(
            parser,
            FIELD_SECURITY_PRIVILEGE_SET
        );
        requireField(parser, FIELD_RESOURCES_MAP, ROLE_FIELDS);
        Map<String, ResourceDef> resources = readResourcesMap(parser);
        requireField(parser, FIELD_BUSINESS_ROLES, ROLE_FIELDS);
        Set<String> businessRoles = readCanonicalStringSet(parser, FIELD_BUSINESS_ROLES);
        requireObjectEnd(parser, ROLE_FIELDS, "role object");

        RoleDef role = new RoleDef(roleId, name);
        role.securityPrivilegeSet.addAll(securityPrivileges);
        role.resourcesMap.putAll(resources);
        role.businessRoles.addAll(businessRoles);
        return role;
    }

    private static ValidatedRole validateRoleForEncode(RoleDef role) {
        validateRoleIdentityForEncode(role);
        validateNullableStringForEncode(FIELD_NAME, role.name);
        List<CanonicalString> securityPrivileges = canonicalStringSet(
            FIELD_SECURITY_PRIVILEGE_SET,
            role.securityPrivilegeSet
        );
        List<ResourceEntry> resources = canonicalResources(role.resourcesMap);
        List<CanonicalString> businessRoles = canonicalStringSet(
            FIELD_BUSINESS_ROLES,
            role.businessRoles
        );
        return new ValidatedRole(securityPrivileges, resources, businessRoles);
    }

    private static void writeStringArray(
        JsonGenerator generator,
        String field,
        List<CanonicalString> values
    ) throws IOException {
        generator.writeArrayFieldStart(field);
        for (CanonicalString value : values) {
            generator.writeString(value.value);
        }
        generator.writeEndArray();
    }

    private static Set<String> readCanonicalStringSet(JsonParser parser, String field)
        throws IOException {
        requireToken(parser.nextToken(), JsonToken.START_ARRAY, field + " array");
        Set<String> values = new LinkedHashSet<>();
        byte[] previous = null;
        while (parser.nextToken() != JsonToken.END_ARRAY) {
            if (parser.currentToken() != JsonToken.VALUE_STRING) {
                throw violation(field + " must contain only non-null strings");
            }
            ensureCollectionRoom(values.size(), field);
            String value = validateDecodedString(field + " element", parser.getText());
            byte[] current = strictUtf8ForDecode(field + " element", value);
            if (previous != null && Arrays.compareUnsigned(previous, current) >= 0) {
                throw violation(
                    field + " elements must be unique and in canonical unsigned UTF-8 order"
                );
            }
            if (!values.add(value)) {
                throw violation(field + " elements must be unique");
            }
            previous = current;
        }
        return values;
    }

    private static List<CanonicalString> canonicalStringSet(
        String field,
        Set<String> values
    ) {
        if (values == null) {
            throw new IllegalArgumentException(field + " must not be null");
        }
        List<CanonicalString> canonical = new ArrayList<>();
        for (String value : values) {
            ensureCollectionRoomForEncode(canonical.size(), field);
            if (value == null) {
                throw new IllegalArgumentException(field + " must not contain null elements");
            }
            canonical.add(new CanonicalString(
                value,
                requiredStringUtf8ForEncode(field + " element", value, false)
            ));
        }
        canonical.sort(CanonicalString::compareTo);
        for (int index = 1; index < canonical.size(); index++) {
            if (canonical.get(index - 1).compareTo(canonical.get(index)) == 0) {
                throw new IllegalArgumentException(field + " elements must be unique");
            }
        }
        return List.copyOf(canonical);
    }

    private static void writeResourcesArray(
        JsonGenerator generator,
        List<ResourceEntry> resources
    ) throws IOException {
        generator.writeArrayFieldStart(FIELD_RESOURCES_MAP);
        for (ResourceEntry entry : resources) {
            generator.writeStartObject();
            generator.writeStringField(FIELD_KEY, entry.key.value);
            generator.writeFieldName(FIELD_VALUE);
            writeResourceObject(generator, entry.value);
            generator.writeEndObject();
        }
        generator.writeEndArray();
    }

    private static Map<String, ResourceDef> readResourcesMap(JsonParser parser)
        throws IOException {
        requireToken(parser.nextToken(), JsonToken.START_ARRAY, FIELD_RESOURCES_MAP + " array");
        Map<String, ResourceDef> resources = new LinkedHashMap<>();
        byte[] previousKey = null;
        while (parser.nextToken() != JsonToken.END_ARRAY) {
            requireToken(parser.currentToken(), JsonToken.START_OBJECT, "resource-map entry");
            ensureCollectionRoom(resources.size(), FIELD_RESOURCES_MAP);
            requireField(parser, FIELD_KEY, RESOURCE_ENTRY_FIELDS);
            String key = readRequiredString(parser, FIELD_KEY, true);
            byte[] keyBytes = strictUtf8ForDecode(FIELD_KEY, key);
            if (previousKey != null && Arrays.compareUnsigned(previousKey, keyBytes) >= 0) {
                throw violation(
                    "resource-map keys must be unique and in canonical unsigned UTF-8 order"
                );
            }
            requireField(parser, FIELD_VALUE, RESOURCE_ENTRY_FIELDS);
            requireToken(parser.nextToken(), JsonToken.START_OBJECT, "resource value object");
            ResourceDef resource = readResourceObject(parser);
            requireObjectEnd(parser, RESOURCE_ENTRY_FIELDS, "resource-map entry");
            if (!key.equals(resource.getResourceName())) {
                throw violation("resource-map key must equal ResourceDef.resourceName");
            }
            if (resources.putIfAbsent(key, resource) != null) {
                throw violation("resource-map keys must be unique");
            }
            previousKey = keyBytes;
        }
        return resources;
    }

    private static List<ResourceEntry> canonicalResources(Map<String, ResourceDef> resources) {
        if (resources == null) {
            throw new IllegalArgumentException(FIELD_RESOURCES_MAP + " must not be null");
        }
        List<ResourceEntry> canonical = new ArrayList<>();
        for (Map.Entry<String, ResourceDef> entry : resources.entrySet()) {
            ensureCollectionRoomForEncode(canonical.size(), FIELD_RESOURCES_MAP);
            String key = entry.getKey();
            if (key == null) {
                throw new IllegalArgumentException(
                    FIELD_RESOURCES_MAP + " must not contain null keys"
                );
            }
            ResourceDef value = entry.getValue();
            if (value == null) {
                throw new IllegalArgumentException(
                    FIELD_RESOURCES_MAP + " must not contain null values"
                );
            }
            byte[] keyBytes = requiredStringUtf8ForEncode(FIELD_KEY, key, true);
            validateResourceIdentityForEncode(value);
            if (!key.equals(value.getResourceName())) {
                throw new IllegalArgumentException(
                    "resource-map key must equal ResourceDef.resourceName"
                );
            }
            canonical.add(new ResourceEntry(new CanonicalString(key, keyBytes), value));
        }
        canonical.sort(ResourceEntry::compareTo);
        for (int index = 1; index < canonical.size(); index++) {
            if (canonical.get(index - 1).compareTo(canonical.get(index)) == 0) {
                throw new IllegalArgumentException("resource-map keys must be unique");
            }
        }
        return List.copyOf(canonical);
    }

    private static void writeResourceObject(JsonGenerator generator, ResourceDef resource)
        throws IOException {
        validateResourceIdentityForEncode(resource);
        generator.writeStartObject();
        generator.writeStringField(FIELD_RESOURCE_NAME, resource.getResourceName());
        generator.writeArrayFieldStart(FIELD_PRIVILEGES_LIST);
        writePrivilegeList(generator, resource.getPrivilegesList());
        generator.writeEndArray();
        writeNullablePrivilege(
            generator,
            FIELD_AGGREGATED_WRITE_PRIVILEGE,
            resource.getAggregatedWritePrivilege()
        );
        writeNullablePrivilege(
            generator,
            FIELD_AGGREGATED_READ_PRIVILEGE,
            resource.getAggregatedReadPrivilege()
        );
        writeNullablePrivilege(
            generator,
            FIELD_AGGREGATED_EXECUTE_PRIVILEGE,
            resource.getAggregatedExecutePrivilege()
        );
        generator.writeEndObject();
    }

    private static ResourceDef readResourceObject(JsonParser parser) throws IOException {
        requireToken(parser.currentToken(), JsonToken.START_OBJECT, "resource object");
        requireField(parser, FIELD_RESOURCE_NAME, RESOURCE_FIELDS);
        String resourceName = readRequiredString(parser, FIELD_RESOURCE_NAME, true);
        requireField(parser, FIELD_PRIVILEGES_LIST, RESOURCE_FIELDS);
        List<PrivilegeDef> privileges = readPrivilegeList(parser);
        requireField(parser, FIELD_AGGREGATED_WRITE_PRIVILEGE, RESOURCE_FIELDS);
        PrivilegeDef write = readNullablePrivilege(parser, FIELD_AGGREGATED_WRITE_PRIVILEGE);
        requireField(parser, FIELD_AGGREGATED_READ_PRIVILEGE, RESOURCE_FIELDS);
        PrivilegeDef read = readNullablePrivilege(parser, FIELD_AGGREGATED_READ_PRIVILEGE);
        requireField(parser, FIELD_AGGREGATED_EXECUTE_PRIVILEGE, RESOURCE_FIELDS);
        PrivilegeDef execute = readNullablePrivilege(
            parser,
            FIELD_AGGREGATED_EXECUTE_PRIVILEGE
        );
        requireObjectEnd(parser, RESOURCE_FIELDS, "resource object");

        ResourceDef resource = new ResourceDef(resourceName);
        resource.setPrivilegesList(privileges);
        resource.setAggregatedWritePrivilege(write);
        resource.setAggregatedReadPrivilege(read);
        resource.setAggregatedExecutePrivilege(execute);
        return resource;
    }

    private static void validateResourceIdentityForEncode(ResourceDef resource) {
        if (resource == null) {
            throw new IllegalArgumentException("resource must not be null");
        }
        if (resource.getClass() != ResourceDef.class) {
            throw new IllegalArgumentException("resource must have the exact ResourceDef class");
        }
        requiredStringUtf8ForEncode(
            FIELD_RESOURCE_NAME,
            resource.getResourceName(),
            true
        );
    }

    private static void writePrivilegeList(
        JsonGenerator generator,
        List<PrivilegeDef> privileges
    ) throws IOException {
        if (privileges == null) {
            throw new IllegalArgumentException(FIELD_PRIVILEGES_LIST + " must not be null");
        }
        Set<PrivilegeDef> uniqueValues = new HashSet<>();
        Set<String> uniqueNames = new HashSet<>();
        int count = 0;
        for (PrivilegeDef privilege : privileges) {
            ensureCollectionRoomForEncode(count, FIELD_PRIVILEGES_LIST);
            if (privilege == null) {
                throw new IllegalArgumentException(
                    FIELD_PRIVILEGES_LIST + " must not contain null elements"
                );
            }
            validatePrivilegeForEncode(privilege);
            requireUniquePrivilegeForEncode(privilege, uniqueValues, uniqueNames);
            writeValidatedPrivilegeObject(generator, privilege);
            count++;
        }
    }

    private static List<PrivilegeDef> readPrivilegeList(JsonParser parser) throws IOException {
        requireToken(parser.nextToken(), JsonToken.START_ARRAY, FIELD_PRIVILEGES_LIST + " array");
        List<PrivilegeDef> privileges = new ArrayList<>();
        Set<PrivilegeDef> uniqueValues = new HashSet<>();
        Set<String> uniqueNames = new HashSet<>();
        while (parser.nextToken() != JsonToken.END_ARRAY) {
            ensureCollectionRoom(privileges.size(), FIELD_PRIVILEGES_LIST);
            requireToken(parser.currentToken(), JsonToken.START_OBJECT, "privilege-list element");
            PrivilegeDef privilege = readPrivilegeObject(parser);
            requireUniquePrivilegeForDecode(privilege, uniqueValues, uniqueNames);
            privileges.add(privilege);
        }
        return privileges;
    }

    private static void requireUniquePrivilegeForEncode(
        PrivilegeDef privilege,
        Set<PrivilegeDef> values,
        Set<String> names
    ) {
        if (!values.add(privilege)) {
            throw new IllegalArgumentException(
                FIELD_PRIVILEGES_LIST + " must not contain duplicate elements"
            );
        }
        if (privilege.name != null && !names.add(privilege.name)) {
            throw new IllegalArgumentException(
                FIELD_PRIVILEGES_LIST + " must not contain duplicate privilege names"
            );
        }
    }

    private static void requireUniquePrivilegeForDecode(
        PrivilegeDef privilege,
        Set<PrivilegeDef> values,
        Set<String> names
    ) {
        if (!values.add(privilege)) {
            throw violation(FIELD_PRIVILEGES_LIST + " contains duplicate elements");
        }
        if (privilege.name != null && !names.add(privilege.name)) {
            throw violation(FIELD_PRIVILEGES_LIST + " contains duplicate privilege names");
        }
    }

    private static void writeNullablePrivilege(
        JsonGenerator generator,
        String field,
        PrivilegeDef privilege
    ) throws IOException {
        generator.writeFieldName(field);
        if (privilege == null) {
            generator.writeNull();
        } else {
            writePrivilegeObject(generator, privilege);
        }
    }

    private static PrivilegeDef readNullablePrivilege(JsonParser parser, String field)
        throws IOException {
        JsonToken token = parser.nextToken();
        if (token == JsonToken.VALUE_NULL) {
            return null;
        }
        requireToken(token, JsonToken.START_OBJECT, field + " privilege object or explicit null");
        return readPrivilegeObject(parser);
    }

    private static void writePrivilegeObject(
        JsonGenerator generator,
        PrivilegeDef privilege
    ) throws IOException {
        validatePrivilegeForEncode(privilege);
        writeValidatedPrivilegeObject(generator, privilege);
    }

    private static void writeValidatedPrivilegeObject(
        JsonGenerator generator,
        PrivilegeDef privilege
    ) throws IOException {
        generator.writeStartObject();
        writeNullableString(generator, FIELD_NAME, privilege.name);
        writeNullableString(generator, FIELD_RESOURCE_NAME, privilege.resourceName);
        generator.writeStringField(FIELD_OPERATION, operationToken(privilege.operation));
        generator.writeBooleanField(FIELD_ALL, privilege.all);
        generator.writeStringField(FIELD_COMPANY, directionToken(privilege.company));
        generator.writeStringField(FIELD_ORG, directionToken(privilege.org));
        generator.writeBooleanField(FIELD_PERSON, privilege.person);
        generator.writeEndObject();
    }

    private static PrivilegeDef readPrivilegeObject(JsonParser parser) throws IOException {
        requireToken(parser.currentToken(), JsonToken.START_OBJECT, "privilege object");

        requireField(parser, FIELD_NAME, PRIVILEGE_FIELDS);
        String name = readNullableString(parser, FIELD_NAME);
        requireField(parser, FIELD_RESOURCE_NAME, PRIVILEGE_FIELDS);
        String resourceName = readNullableString(parser, FIELD_RESOURCE_NAME);
        requireField(parser, FIELD_OPERATION, PRIVILEGE_FIELDS);
        PrivilegeOperation operation = parseOperation(readRequiredString(
            parser,
            FIELD_OPERATION,
            false
        ));
        requireField(parser, FIELD_ALL, PRIVILEGE_FIELDS);
        boolean all = readBoolean(parser, FIELD_ALL);
        requireField(parser, FIELD_COMPANY, PRIVILEGE_FIELDS);
        PrivilegeDirection company = parseDirection(readRequiredString(
            parser,
            FIELD_COMPANY,
            false
        ));
        requireField(parser, FIELD_ORG, PRIVILEGE_FIELDS);
        PrivilegeDirection org = parseDirection(readRequiredString(parser, FIELD_ORG, false));
        requireField(parser, FIELD_PERSON, PRIVILEGE_FIELDS);
        boolean person = readBoolean(parser, FIELD_PERSON);
        requireObjectEnd(parser, PRIVILEGE_FIELDS, "privilege object");

        PrivilegeDef privilege = new PrivilegeDef(name, resourceName);
        privilege.operation = operation;
        privilege.all = all;
        privilege.company = company;
        privilege.org = org;
        privilege.person = person;
        return privilege;
    }

    private static void validatePrivilegeForEncode(PrivilegeDef privilege) {
        if (privilege == null) {
            throw new IllegalArgumentException("privilege must not be null");
        }
        if (privilege.getClass() != PrivilegeDef.class) {
            throw new IllegalArgumentException("privilege must have the exact PrivilegeDef class");
        }
        validateNullableStringForEncode(FIELD_NAME, privilege.name);
        validateNullableStringForEncode(FIELD_RESOURCE_NAME, privilege.resourceName);
        if (privilege.operation == null) {
            throw new IllegalArgumentException("operation must not be null");
        }
        if (privilege.company == null) {
            throw new IllegalArgumentException("company must not be null");
        }
        if (privilege.org == null) {
            throw new IllegalArgumentException("org must not be null");
        }
    }

    private static void requireBoundedInput(byte[] payload, PayloadKind kind) {
        if (payload == null) {
            throw corrupt(kind, "payload is null");
        }
        if (payload.length == 0) {
            throw corrupt(kind, "payload is empty");
        }
        if (payload.length > kind.maxPayloadBytes) {
            throw corrupt(kind, "payload exceeds " + kind.maxPayloadBytes + " bytes");
        }
    }

    private static void requireEndOfDocument(JsonParser parser) throws IOException {
        if (parser.nextToken() != null) {
            throw violation("trailing JSON content is forbidden");
        }
    }

    private static void requireField(
        JsonParser parser,
        String expected,
        Set<String> schemaFields
    ) throws IOException {
        JsonToken token = parser.nextToken();
        if (token == JsonToken.END_OBJECT || token == null) {
            throw violation("missing required field " + expected);
        }
        if (token != JsonToken.FIELD_NAME) {
            throw violation("expected required field " + expected);
        }
        String actual = parser.currentName();
        if (!expected.equals(actual)) {
            if (schemaFields.contains(actual)) {
                throw violation("field order is not canonical; expected " + expected);
            }
            throw violation("unknown field where " + expected + " was required");
        }
    }

    private static void requireObjectEnd(
        JsonParser parser,
        Set<String> schemaFields,
        String description
    ) throws IOException {
        JsonToken end = parser.nextToken();
        if (end == JsonToken.END_OBJECT) {
            return;
        }
        if (end == JsonToken.FIELD_NAME) {
            throw violation(schemaFields.contains(parser.currentName())
                ? "duplicate or out-of-order field after the canonical schema"
                : "unknown field after the canonical schema");
        }
        throw violation("expected the end of the " + description);
    }

    private static long readRequiredLong(JsonParser parser, String field) throws IOException {
        JsonToken token = parser.nextToken();
        if (token != JsonToken.VALUE_NUMBER_INT) {
            throw violation(field + " must be a canonical 64-bit JSON integer");
        }
        return parser.getLongValue();
    }

    private static Long readNullableLong(JsonParser parser, String field) throws IOException {
        JsonToken token = parser.nextToken();
        if (token == JsonToken.VALUE_NULL) {
            return null;
        }
        if (token != JsonToken.VALUE_NUMBER_INT) {
            throw violation(field + " must be a canonical 64-bit JSON integer or explicit null");
        }
        return parser.getLongValue();
    }

    private static void writeNullableLong(
        JsonGenerator generator,
        String field,
        Long value
    ) throws IOException {
        if (value == null) {
            generator.writeNullField(field);
        } else {
            generator.writeNumberField(field, value);
        }
    }

    private static void writeNullableLongList(
        JsonGenerator generator,
        String field,
        List<Long> values
    ) throws IOException {
        if (values == null) {
            generator.writeNullField(field);
            return;
        }
        if (values.size() > MAX_COLLECTION_ENTRIES) {
            throw new IllegalArgumentException(field + " exceeds " + MAX_COLLECTION_ENTRIES + " entries");
        }
        generator.writeArrayFieldStart(field);
        for (Long value : values) {
            if (value == null) {
                throw new IllegalArgumentException(field + " must not contain null");
            }
            generator.writeNumber(value);
        }
        generator.writeEndArray();
    }

    private static List<Long> readNullableLongList(JsonParser parser, String field) throws IOException {
        JsonToken token = parser.nextToken();
        if (token == JsonToken.VALUE_NULL) {
            return null;
        }
        if (token != JsonToken.START_ARRAY) {
            throw violation(field + " must be a JSON array or explicit null");
        }
        List<Long> values = new ArrayList<>();
        while (parser.nextToken() != JsonToken.END_ARRAY) {
            if (values.size() >= MAX_COLLECTION_ENTRIES) {
                throw violation(field + " exceeds " + MAX_COLLECTION_ENTRIES + " entries");
            }
            if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT) {
                throw violation(field + " entries must be canonical 64-bit JSON integers");
            }
            values.add(parser.getLongValue());
        }
        return List.copyOf(values);
    }

    private static String readNullableString(JsonParser parser, String field) throws IOException {
        return readNullableBoundedString(
            parser,
            field,
            MAX_STRING_UTF16_UNITS,
            MAX_STRING_UTF8_BYTES
        );
    }

    private static String readNullablePathString(JsonParser parser, String field)
        throws IOException {
        return readNullableBoundedString(
            parser,
            field,
            MAX_PATH_UTF16_UNITS,
            MAX_PATH_UTF8_BYTES
        );
    }

    private static String readNullableFilter(JsonParser parser) throws IOException {
        return readNullableBoundedString(
            parser,
            FIELD_FILTER,
            MAX_FILTER_UTF16_UNITS,
            MAX_FILTER_UTF8_BYTES
        );
    }

    private static String readNullableBoundedString(
        JsonParser parser,
        String field,
        int maxUtf16Units,
        int maxUtf8Bytes
    ) throws IOException {
        JsonToken token = parser.nextToken();
        if (token == JsonToken.VALUE_NULL) {
            return null;
        }
        if (token != JsonToken.VALUE_STRING) {
            throw violation(field + " must be a string or explicit null");
        }
        return validateDecodedString(
            field,
            parser.getText(),
            maxUtf16Units,
            maxUtf8Bytes
        );
    }

    private static String readRequiredString(
        JsonParser parser,
        String field,
        boolean requireNonBlank
    ) throws IOException {
        JsonToken token = parser.nextToken();
        if (token != JsonToken.VALUE_STRING) {
            throw violation(field + " must be a non-null string");
        }
        String value = validateDecodedString(field, parser.getText());
        if (requireNonBlank && value.isBlank()) {
            throw violation(field + " must not be blank");
        }
        return value;
    }

    private static String validateDecodedString(String field, String value) {
        return validateDecodedString(
            field,
            value,
            MAX_STRING_UTF16_UNITS,
            MAX_STRING_UTF8_BYTES
        );
    }

    private static String validateDecodedString(
        String field,
        String value,
        int maxUtf16Units,
        int maxUtf8Bytes
    ) {
        if (value.length() > maxUtf16Units) {
            if (maxUtf16Units == MAX_STRING_UTF16_UNITS) {
                throw new ParserBoundViolationException();
            }
            throw violation(field + " exceeds " + maxUtf16Units + " UTF-16 units");
        }
        byte[] utf8 = strictUtf8ForDecode(field, value);
        if (utf8.length > maxUtf8Bytes) {
            if (maxUtf8Bytes == MAX_STRING_UTF8_BYTES) {
                throw new ParserBoundViolationException();
            }
            throw violation(field + " exceeds " + maxUtf8Bytes + " UTF-8 bytes");
        }
        return value;
    }

    private static byte[] strictUtf8ForDecode(String field, String value) {
        try {
            return strictUtf8(value);
        } catch (CharacterCodingException exception) {
            throw violation(field + " is not well-formed Unicode");
        }
    }

    private static boolean readBoolean(JsonParser parser, String field) throws IOException {
        JsonToken token = parser.nextToken();
        if (token == JsonToken.VALUE_TRUE) {
            return true;
        }
        if (token == JsonToken.VALUE_FALSE) {
            return false;
        }
        throw violation(field + " must be a JSON boolean");
    }

    private static void validateNullableStringForEncode(String field, String value) {
        if (value != null) {
            requiredStringUtf8ForEncode(field, value, false);
        }
    }

    private static void validateNullablePathForEncode(String field, String value) {
        if (value != null) {
            requiredStringUtf8ForEncode(
                field,
                value,
                false,
                MAX_PATH_UTF16_UNITS,
                MAX_PATH_UTF8_BYTES
            );
        }
    }

    private static void validateNullableFilterForEncode(String value) {
        if (value != null) {
            requiredStringUtf8ForEncode(
                FIELD_FILTER,
                value,
                false,
                MAX_FILTER_UTF16_UNITS,
                MAX_FILTER_UTF8_BYTES
            );
        }
    }

    private static byte[] requiredStringUtf8ForEncode(
        String field,
        String value,
        boolean requireNonBlank
    ) {
        return requiredStringUtf8ForEncode(
            field,
            value,
            requireNonBlank,
            MAX_STRING_UTF16_UNITS,
            MAX_STRING_UTF8_BYTES
        );
    }

    private static byte[] requiredStringUtf8ForEncode(
        String field,
        String value,
        boolean requireNonBlank,
        int maxUtf16Units,
        int maxUtf8Bytes
    ) {
        if (value == null) {
            throw new IllegalArgumentException(field + " must not be null");
        }
        if (requireNonBlank && value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        if (value.length() > maxUtf16Units) {
            throw new IllegalArgumentException(
                field + " exceeds " + maxUtf16Units + " UTF-16 units"
            );
        }
        byte[] utf8;
        try {
            utf8 = strictUtf8(value);
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException(field + " is not well-formed Unicode", exception);
        }
        if (utf8.length > maxUtf8Bytes) {
            throw new IllegalArgumentException(
                field + " exceeds " + maxUtf8Bytes + " UTF-8 bytes"
            );
        }
        return utf8;
    }

    private static void writeNullableString(
        JsonGenerator generator,
        String field,
        String value
    ) throws IOException {
        generator.writeFieldName(field);
        if (value == null) {
            generator.writeNull();
        } else {
            generator.writeString(value);
        }
    }

    private static PrivilegeOperation parseOperation(String token) {
        return switch (token) {
            case "NONE" -> PrivilegeOperation.NONE;
            case "READ" -> PrivilegeOperation.READ;
            case "WRITE" -> PrivilegeOperation.WRITE;
            case "EXECUTE" -> PrivilegeOperation.EXECUTE;
            default -> throw violation("operation is not a canonical enum token");
        };
    }

    private static String operationToken(PrivilegeOperation operation) {
        return switch (operation) {
            case NONE -> "NONE";
            case READ -> "READ";
            case WRITE -> "WRITE";
            case EXECUTE -> "EXECUTE";
        };
    }

    private static PrivilegeDirection parseDirection(String token) {
        return switch (token) {
            case "NONE" -> PrivilegeDirection.NONE;
            case "EXACT" -> PrivilegeDirection.EXACT;
            case "HIERARCHY_DOWN" -> PrivilegeDirection.HIERARCHY_DOWN;
            case "HIERARCHY_UP" -> PrivilegeDirection.HIERARCHY_UP;
            case "ALL" -> PrivilegeDirection.ALL;
            default -> throw violation("direction is not a canonical enum token");
        };
    }

    private static String directionToken(PrivilegeDirection direction) {
        return switch (direction) {
            case NONE -> "NONE";
            case EXACT -> "EXACT";
            case HIERARCHY_DOWN -> "HIERARCHY_DOWN";
            case HIERARCHY_UP -> "HIERARCHY_UP";
            case ALL -> "ALL";
        };
    }

    private static void requireToken(JsonToken actual, JsonToken expected, String description) {
        if (actual != expected) {
            throw violation("expected " + description);
        }
    }

    private static void ensureCollectionRoom(int currentSize, String field) {
        if (currentSize >= MAX_COLLECTION_ENTRIES) {
            throw violation(field + " exceeds " + MAX_COLLECTION_ENTRIES + " elements");
        }
    }

    private static void ensureCollectionRoomForEncode(int currentSize, String field) {
        if (currentSize >= MAX_COLLECTION_ENTRIES) {
            throw new IllegalArgumentException(
                field + " exceeds " + MAX_COLLECTION_ENTRIES + " elements"
            );
        }
    }

    private static void requireCanonicalMapOrder(
        byte[] previous,
        byte[] current,
        String field
    ) {
        if (previous != null && Arrays.compareUnsigned(previous, current) >= 0) {
            throw violation(
                field + " keys must be unique and in canonical unsigned UTF-8 order"
            );
        }
    }

    private static long parseCanonicalLongKey(String field, String token) {
        final long value;
        try {
            value = Long.parseLong(token);
        } catch (NumberFormatException exception) {
            throw violation(field + " key must be a canonical 64-bit decimal integer");
        }
        if (!Long.toString(value).equals(token)) {
            throw violation(field + " key must be a canonical 64-bit decimal integer");
        }
        return value;
    }

    private static CanonicalString canonicalLongKey(long value) {
        String token = Long.toString(value);
        return new CanonicalString(token, token.getBytes(StandardCharsets.US_ASCII));
    }

    private static void requireUniqueCanonicalEntries(
        List<? extends CanonicalKeyed> entries,
        String field
    ) {
        for (int index = 1; index < entries.size(); index++) {
            if (entries.get(index - 1).canonicalKey().compareTo(
                entries.get(index).canonicalKey()
            ) == 0) {
                throw new IllegalArgumentException(field + " must contain unique identities");
            }
        }
    }

    private static byte[] strictUtf8(String value) throws CharacterCodingException {
        ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(CharBuffer.wrap(value));
        byte[] bytes = new byte[encoded.remaining()];
        encoded.get(bytes);
        return bytes;
    }

    private static SchemaViolationException violation(String detail) {
        return new SchemaViolationException(detail);
    }

    private static RedisProtocolException corrupt(PayloadKind kind, String detail) {
        return switch (kind) {
            case PRIVILEGE -> new CorruptPrivilegePayloadException(detail);
            case ROLE -> new CorruptRolePayloadException(detail);
            case ORGANIZATION -> new CorruptOrganizationPayloadException(detail);
            case PERSON -> new CorruptPersonPayloadException(detail);
        };
    }

    @FunctionalInterface
    private interface PayloadWriter {
        void write(JsonGenerator generator) throws IOException;
    }

    private enum PayloadKind {
        PRIVILEGE("privilege", MAX_PRIVILEGE_PAYLOAD_BYTES),
        ROLE("role", MAX_ROLE_PAYLOAD_BYTES),
        ORGANIZATION("organization", MAX_ORGANIZATION_PAYLOAD_BYTES),
        PERSON("person", MAX_PERSON_PAYLOAD_BYTES);

        private final String label;
        private final int maxPayloadBytes;

        PayloadKind(String label, int maxPayloadBytes) {
            this.label = label;
            this.maxPayloadBytes = maxPayloadBytes;
        }
    }

    private interface CanonicalKeyed {
        CanonicalString canonicalKey();
    }

    private static final class CanonicalString {
        private final String value;
        private final byte[] utf8;

        private CanonicalString(String value, byte[] utf8) {
            this.value = value;
            this.utf8 = utf8;
        }

        private int compareTo(CanonicalString other) {
            return Arrays.compareUnsigned(utf8, other.utf8);
        }
    }

    private static final class ResourceEntry {
        private final CanonicalString key;
        private final ResourceDef value;

        private ResourceEntry(CanonicalString key, ResourceDef value) {
            this.key = key;
            this.value = value;
        }

        private int compareTo(ResourceEntry other) {
            return key.compareTo(other.key);
        }
    }

    private static final class OrganizationEntry implements CanonicalKeyed {
        private final CanonicalString key;
        private final OrganizationDef value;

        private OrganizationEntry(CanonicalString key, OrganizationDef value) {
            this.key = key;
            this.value = value;
        }

        private int compareTo(OrganizationEntry other) {
            return key.compareTo(other.key);
        }

        @Override
        public CanonicalString canonicalKey() {
            return key;
        }
    }

    private static final class RoleEntry implements CanonicalKeyed {
        private final CanonicalString key;
        private final RoleDef value;

        private RoleEntry(CanonicalString key, RoleDef value) {
            this.key = key;
            this.value = value;
        }

        private int compareTo(RoleEntry other) {
            return key.compareTo(other.key);
        }

        @Override
        public CanonicalString canonicalKey() {
            return key;
        }
    }

    private static final class BusinessRoleEntry implements CanonicalKeyed {
        private final CanonicalString key;
        private final BusinessRoleDef value;

        private BusinessRoleEntry(CanonicalString key, BusinessRoleDef value) {
            this.key = key;
            this.value = value;
        }

        private int compareTo(BusinessRoleEntry other) {
            return key.compareTo(other.key);
        }

        @Override
        public CanonicalString canonicalKey() {
            return key;
        }
    }

    private static final class ValidatedPerson {
        private final List<OrganizationEntry> organizations;

        private ValidatedPerson(List<OrganizationEntry> organizations) {
            this.organizations = organizations;
        }
    }

    private static final class ValidatedOrganization {
        private final List<RoleEntry> positionRoles;
        private final List<RoleEntry> organizationRoles;
        private final List<BusinessRoleEntry> businessRoles;

        private ValidatedOrganization(
            List<RoleEntry> positionRoles,
            List<RoleEntry> organizationRoles,
            List<BusinessRoleEntry> businessRoles
        ) {
            this.positionRoles = positionRoles;
            this.organizationRoles = organizationRoles;
            this.businessRoles = businessRoles;
        }
    }

    private static final class ValidatedRole {
        private final List<CanonicalString> securityPrivileges;
        private final List<ResourceEntry> resources;
        private final List<CanonicalString> businessRoles;

        private ValidatedRole(
            List<CanonicalString> securityPrivileges,
            List<ResourceEntry> resources,
            List<CanonicalString> businessRoles
        ) {
            this.securityPrivileges = securityPrivileges;
            this.resources = resources;
            this.businessRoles = businessRoles;
        }
    }

    /**
     * Byte accumulator whose backing buffer can never grow beyond the wire payload limit.
     * Jackson may buffer internally, but the first flush that would cross the limit fails before
     * any oversized byte is appended to this accumulator.
     */
    private static final class HardBoundedByteArrayOutputStream extends OutputStream {

        private final int maxBytes;
        private byte[] buffer;
        private int size;

        private HardBoundedByteArrayOutputStream(int initialCapacity, int maxBytes) {
            if (initialCapacity < 0 || maxBytes < 0) {
                throw new IllegalArgumentException("stream capacities must not be negative");
            }
            this.maxBytes = maxBytes;
            this.buffer = new byte[Math.min(initialCapacity, maxBytes)];
        }

        @Override
        public void write(int value) throws IOException {
            requireRoom(1);
            buffer[size] = (byte) value;
            size++;
        }

        @Override
        public void write(byte[] value, int offset, int length) throws IOException {
            if (value == null) {
                throw new NullPointerException("value must not be null");
            }
            if (offset < 0 || length < 0 || length > value.length - offset) {
                throw new IndexOutOfBoundsException("invalid byte range");
            }
            requireRoom(length);
            System.arraycopy(value, offset, buffer, size, length);
            size += length;
        }

        private void requireRoom(int additionalBytes) throws OutputLimitExceededException {
            if (additionalBytes > maxBytes - size) {
                throw new OutputLimitExceededException();
            }
            int requiredCapacity = size + additionalBytes;
            if (requiredCapacity <= buffer.length) {
                return;
            }
            int doubledCapacity = Math.max(1, buffer.length * 2);
            int nextCapacity = Math.min(
                maxBytes,
                Math.max(requiredCapacity, doubledCapacity)
            );
            buffer = Arrays.copyOf(buffer, nextCapacity);
        }

        private byte[] toByteArray() {
            return Arrays.copyOf(buffer, size);
        }
    }

    private static final class OutputLimitExceededException extends IOException {

        private OutputLimitExceededException() {
            super("canonical payload output limit exceeded");
        }
    }

    private static final class SchemaViolationException extends RuntimeException {
        private SchemaViolationException(String detail) {
            super(detail);
        }
    }

    /** Preserves the original parser-bound diagnostic after widening the shared graph factory. */
    private static final class ParserBoundViolationException extends RuntimeException {}

    /** Stable standalone privilege failure retained from the privilege-only codec. */
    static final class CorruptPrivilegePayloadException extends RedisProtocolException {

        static final String DIAGNOSTIC_CODE = "ORGSEC_STORAGE_REDIS_PRIVILEGE_PAYLOAD_CORRUPT";

        private CorruptPrivilegePayloadException(String detail) {
            super(DIAGNOSTIC_CODE + ": " + detail);
        }
    }

    /** Strict role graph failure kept internal until the snapshot data plane consumes it. */
    static final class CorruptRolePayloadException extends RedisProtocolException {

        static final String DIAGNOSTIC_CODE = "ORGSEC_STORAGE_REDIS_ROLE_PAYLOAD_CORRUPT";

        private CorruptRolePayloadException(String detail) {
            super(DIAGNOSTIC_CODE + ": " + detail);
        }
    }

    /** Strict organization graph failure kept internal to the snapshot data plane. */
    static final class CorruptOrganizationPayloadException extends RedisProtocolException {

        static final String DIAGNOSTIC_CODE =
            "ORGSEC_STORAGE_REDIS_ORGANIZATION_PAYLOAD_CORRUPT";

        private CorruptOrganizationPayloadException(String detail) {
            super(DIAGNOSTIC_CODE + ": " + detail);
        }
    }

    /** Strict person graph failure kept internal to the snapshot data plane. */
    static final class CorruptPersonPayloadException extends RedisProtocolException {

        static final String DIAGNOSTIC_CODE = "ORGSEC_STORAGE_REDIS_PERSON_PAYLOAD_CORRUPT";

        private CorruptPersonPayloadException(String detail) {
            super(DIAGNOSTIC_CODE + ": " + detail);
        }
    }
}
