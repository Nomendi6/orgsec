package com.nomendi6.orgsec.storage.redis.protocol;

import com.nomendi6.orgsec.constants.PrivilegeDirection;
import com.nomendi6.orgsec.constants.PrivilegeOperation;
import com.nomendi6.orgsec.model.PrivilegeDef;
import com.nomendi6.orgsec.model.ResourceDef;
import com.nomendi6.orgsec.model.RoleDef;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RedisCanonicalRolePayloadCodecTest {

    private static final String EMPTY_ROLE =
        "{\"roleId\":1,\"name\":null,\"securityPrivilegeSet\":[]," +
        "\"resourcesMap\":[],\"businessRoles\":[]}";

    private static final String READ_PRIVILEGE =
        "{\"name\":\"invoice.read\",\"resourceName\":\"invoice/東京\"," +
        "\"operation\":\"READ\",\"all\":false,\"company\":\"NONE\"," +
        "\"org\":\"EXACT\",\"person\":true}";

    private static final String WRITE_PRIVILEGE =
        "{\"name\":\"invoice.write\",\"resourceName\":\"invoice/東京\"," +
        "\"operation\":\"WRITE\",\"all\":false,\"company\":\"EXACT\"," +
        "\"org\":\"NONE\",\"person\":false}";

    private static final String AGGREGATED_READ_PRIVILEGE =
        "{\"name\":\"invoice.read+write\",\"resourceName\":\"invoice/東京\"," +
        "\"operation\":\"WRITE\",\"all\":false,\"company\":\"EXACT\"," +
        "\"org\":\"NONE\",\"person\":false}";

    private static final String EXECUTE_PRIVILEGE =
        "{\"name\":\"invoice.execute\",\"resourceName\":\"invoice/東京\"," +
        "\"operation\":\"EXECUTE\",\"all\":true,\"company\":\"ALL\"," +
        "\"org\":\"HIERARCHY_DOWN\",\"person\":true}";

    private static final String FULL_ROLE_GOLDEN =
        "{\"roleId\":7,\"name\":\"Odobravatelj č\"," +
        "\"securityPrivilegeSet\":[\"invoice:approve\",\"račun:čitaj\"]," +
        "\"resourcesMap\":[{\"key\":\"invoice/東京\",\"value\":{" +
        "\"resourceName\":\"invoice/東京\",\"privilegesList\":[" +
        READ_PRIVILEGE + "," + WRITE_PRIVILEGE + "]," +
        "\"aggregatedWritePrivilege\":" + WRITE_PRIVILEGE + "," +
        "\"aggregatedReadPrivilege\":" + AGGREGATED_READ_PRIVILEGE + "," +
        "\"aggregatedExecutePrivilege\":" + EXECUTE_PRIVILEGE + "}}]," +
        "\"businessRoles\":[\"auditor\",\"owner\"]}";

    private static final String EMPTY_RESOURCE =
        "{\"resourceName\":\"r\",\"privilegesList\":[]," +
        "\"aggregatedWritePrivilege\":null,\"aggregatedReadPrivilege\":null," +
        "\"aggregatedExecutePrivilege\":null}";

    private static final String ROLE_WITH_EMPTY_RESOURCE =
        roleJson("[]", "[{\"key\":\"r\",\"value\":" + EMPTY_RESOURCE + "}]", "[]");

    private final RedisCanonicalSnapshotPayloadCodec codec =
        new RedisCanonicalSnapshotPayloadCodec();

    @Test
    void hasStableGoldenBytesForAFullRoleAndRichRoundTrip() {
        RoleDef role = fullRole();

        byte[] encoded = codec.encode(role);
        RoleDef decoded = codec.decodeRole(encoded);

        assertThat(encoded).containsExactly(FULL_ROLE_GOLDEN.getBytes(StandardCharsets.UTF_8));
        assertThat(decoded.getClass()).isEqualTo(RoleDef.class);
        assertThat(decoded.roleId).isEqualTo(7L);
        assertThat(decoded.name).isEqualTo("Odobravatelj č");
        assertThat(decoded.securityPrivilegeSet)
            .containsExactlyInAnyOrder("invoice:approve", "račun:čitaj");
        assertThat(decoded.businessRoles).containsExactlyInAnyOrder("auditor", "owner");
        assertThat(decoded.resourcesMap).containsOnlyKeys("invoice/東京");

        ResourceDef resource = decoded.resourcesMap.get("invoice/東京");
        assertThat(resource.getClass()).isEqualTo(ResourceDef.class);
        assertThat(resource.getResourceName()).isEqualTo("invoice/東京");
        assertThat(resource.getPrivilegesList()).hasSize(2);
        assertPrivilege(
            resource.getPrivilegesList().get(0),
            "invoice.read",
            "invoice/東京",
            PrivilegeOperation.READ,
            false,
            PrivilegeDirection.NONE,
            PrivilegeDirection.EXACT,
            true
        );
        assertPrivilege(
            resource.getPrivilegesList().get(1),
            "invoice.write",
            "invoice/東京",
            PrivilegeOperation.WRITE,
            false,
            PrivilegeDirection.EXACT,
            PrivilegeDirection.NONE,
            false
        );
        assertPrivilege(
            resource.getAggregatedWritePrivilege(),
            "invoice.write",
            "invoice/東京",
            PrivilegeOperation.WRITE,
            false,
            PrivilegeDirection.EXACT,
            PrivilegeDirection.NONE,
            false
        );
        assertPrivilege(
            resource.getAggregatedReadPrivilege(),
            "invoice.read+write",
            "invoice/東京",
            PrivilegeOperation.WRITE,
            false,
            PrivilegeDirection.EXACT,
            PrivilegeDirection.NONE,
            false
        );
        assertPrivilege(
            resource.getAggregatedExecutePrivilege(),
            "invoice.execute",
            "invoice/東京",
            PrivilegeOperation.EXECUTE,
            true,
            PrivilegeDirection.ALL,
            PrivilegeDirection.HIERARCHY_DOWN,
            true
        );
    }

    @Test
    void writesExplicitNullRoleNameAndNullableAggregates() {
        RoleDef role = new RoleDef(1L, null);
        ResourceDef resource = new ResourceDef("r");
        resource.setAggregatedWritePrivilege(null);
        resource.setAggregatedReadPrivilege(null);
        resource.setAggregatedExecutePrivilege(null);
        role.resourcesMap.put("r", resource);

        byte[] encoded = codec.encode(role);

        assertThat(encoded).containsExactly(ROLE_WITH_EMPTY_RESOURCE.getBytes(StandardCharsets.UTF_8));
        ResourceDef decoded = codec.decodeRole(encoded).resourcesMap.get("r");
        assertThat(decoded.getAggregatedWritePrivilege()).isNull();
        assertThat(decoded.getAggregatedReadPrivilege()).isNull();
        assertThat(decoded.getAggregatedExecutePrivilege()).isNull();
    }

    @Test
    void canonicalizesSetsAndResourceMapByUnsignedUtf8RegardlessOfInsertionOrder() {
        RoleDef forward = roleWithTwoResources(false);
        RoleDef reverse = roleWithTwoResources(true);

        byte[] forwardBytes = codec.encode(forward);
        byte[] reverseBytes = codec.encode(reverse);

        assertThat(reverseBytes).containsExactly(forwardBytes);
        assertThat(text(forwardBytes))
            .contains("\"securityPrivilegeSet\":[\"a\",\"\",\"𐀀\"]")
            .contains("\"key\":\"a\"")
            .contains("\"key\":\"\"")
            .contains("\"key\":\"𐀀\"")
            .contains("\"businessRoles\":[\"a\",\"\",\"𐀀\"]");
        assertThat(text(forwardBytes).indexOf("\"key\":\"a\""))
            .isLessThan(text(forwardBytes).indexOf("\"key\":\"\""));
        assertThat(text(forwardBytes).indexOf("\"key\":\"\""))
            .isLessThan(text(forwardBytes).indexOf("\"key\":\"𐀀\""));
    }

    @Test
    void preservesPrivilegeListOrderAndThereforeChangesCanonicalBytesWhenReversed() {
        RoleDef forward = fullRole();
        RoleDef reverse = fullRole();
        ResourceDef resource = reverse.resourcesMap.get("invoice/東京");
        List<PrivilegeDef> reversed = new ArrayList<>(resource.getPrivilegesList());
        java.util.Collections.reverse(reversed);
        resource.setPrivilegesList(reversed);

        assertThat(codec.encode(reverse)).isNotEqualTo(codec.encode(forward));
        RoleDef decoded = codec.decodeRole(codec.encode(reverse));
        assertThat(decoded.resourcesMap.get("invoice/東京").getPrivilegesList())
            .extracting(privilege -> privilege.name)
            .containsExactly("invoice.write", "invoice.read");
    }

    @Test
    void rolePayloadIsFamilyIndependentWhileEntryDigestSeparatesRoleFamilies() {
        byte[] payload = codec.encode(fullRole());
        RedisCanonicalEntry entry = new RedisCanonicalEntry(bytes("7"), payload);

        byte[] partyDigest = RedisSnapshotFamilyAccumulator.entryDigest(
            RedisSnapshotFamilyCode.PARTY_ROLES,
            entry
        );
        byte[] positionDigest = RedisSnapshotFamilyAccumulator.entryDigest(
            RedisSnapshotFamilyCode.POSITION_ROLES,
            entry
        );
        byte[] legacyDigest = RedisSnapshotFamilyAccumulator.entryDigest(
            RedisSnapshotFamilyCode.ROLES,
            entry
        );

        assertThat(partyDigest).isNotEqualTo(positionDigest).isNotEqualTo(legacyDigest);
        assertThat(positionDigest).isNotEqualTo(legacyDigest);
        assertThat(text(payload))
            .doesNotContain("PARTY_ROLES", "POSITION_ROLES", "ROLES", "family");
    }

    @Test
    void rejectsNullSubtypeMissingIdentityAndNullRoleCollectionsOnEncode() {
        assertRoleEncodeFailure(null, "role must not be null");
        assertRoleEncodeFailure(new DerivedRoleDef(), "exact RoleDef class");

        RoleDef missingId = new RoleDef(null, "missing");
        assertRoleEncodeFailure(missingId, "roleId", "must not be null");

        RoleDef nullSecurityPrivileges = new RoleDef(1L, "r");
        nullSecurityPrivileges.securityPrivilegeSet = null;
        assertRoleEncodeFailure(nullSecurityPrivileges, "securityPrivilegeSet", "not be null");

        RoleDef nullResources = new RoleDef(1L, "r");
        nullResources.resourcesMap = null;
        assertRoleEncodeFailure(nullResources, "resourcesMap", "not be null");

        RoleDef nullBusinessRoles = new RoleDef(1L, "r");
        nullBusinessRoles.businessRoles = null;
        assertRoleEncodeFailure(nullBusinessRoles, "businessRoles", "not be null");
    }

    @Test
    void rejectsInvalidSetElementsAndResourceMapIdentitiesOnEncode() {
        RoleDef nullSetElement = new RoleDef(1L, "r");
        nullSetElement.securityPrivilegeSet.add(null);
        assertRoleEncodeFailure(nullSetElement, "securityPrivilegeSet", "null elements");

        RoleDef nullKey = new RoleDef(1L, "r");
        nullKey.resourcesMap.put(null, new ResourceDef("r"));
        assertRoleEncodeFailure(nullKey, "null keys");

        RoleDef nullValue = new RoleDef(1L, "r");
        nullValue.resourcesMap.put("r", null);
        assertRoleEncodeFailure(nullValue, "null values");

        RoleDef blankKey = new RoleDef(1L, "r");
        blankKey.resourcesMap.put(" ", new ResourceDef(" "));
        assertRoleEncodeFailure(blankKey, "key", "blank");

        RoleDef mismatchedKey = new RoleDef(1L, "r");
        mismatchedKey.resourcesMap.put("r", new ResourceDef("other"));
        assertRoleEncodeFailure(mismatchedKey, "key must equal");

        RoleDef malformedKey = new RoleDef(1L, "r");
        malformedKey.resourcesMap.put("bad-\ud800", new ResourceDef("bad-\ud800"));
        assertRoleEncodeFailure(malformedKey, "key", "well-formed Unicode");
    }

    @Test
    void rejectsNullSubclassesAndDuplicatePrivilegeIdentitiesOnEncode() throws Exception {
        RoleDef derivedResourceRole = new RoleDef(1L, "r");
        derivedResourceRole.resourcesMap.put("r", new DerivedResourceDef());
        assertRoleEncodeFailure(derivedResourceRole, "exact ResourceDef class");

        ResourceDef nullList = new ResourceDef("r");
        Field privilegesList = ResourceDef.class.getDeclaredField("privilegesList");
        privilegesList.setAccessible(true);
        privilegesList.set(nullList, null);
        RoleDef nullListRole = roleWithResource(nullList);
        assertRoleEncodeFailure(nullListRole, "privilegesList", "not be null");

        ResourceDef nullElement = emptyNullableResource("r");
        nullElement.getPrivilegesList().add(null);
        assertRoleEncodeFailure(roleWithResource(nullElement), "privilegesList", "null");

        ResourceDef derivedPrivilege = emptyNullableResource("r");
        derivedPrivilege.getPrivilegesList().add(new DerivedPrivilegeDef());
        assertRoleEncodeFailure(roleWithResource(derivedPrivilege), "exact PrivilegeDef class");

        ResourceDef duplicateElement = emptyNullableResource("r");
        PrivilegeDef same = privilege("same", "r", PrivilegeOperation.READ);
        duplicateElement.setPrivilegesList(List.of(same, same));
        assertRoleEncodeFailure(
            roleWithResource(duplicateElement),
            "privilegesList",
            "duplicate elements"
        );

        ResourceDef duplicateName = emptyNullableResource("r");
        duplicateName.setPrivilegesList(List.of(
            privilege("same-name", "r", PrivilegeOperation.READ),
            privilege("same-name", "r", PrivilegeOperation.WRITE)
        ));
        assertRoleEncodeFailure(
            roleWithResource(duplicateName),
            "privilegesList",
            "duplicate privilege names"
        );
    }

    @Test
    void rejectsMissingUnknownDuplicateOutOfOrderAndTrailingRoleFields() {
        assertCorrupt(
            EMPTY_ROLE.replace(",\"businessRoles\":[]", ""),
            "missing required field businessRoles"
        );
        assertCorrupt(
            EMPTY_ROLE.replace("\"name\":null", "\"future\":null"),
            "unknown field"
        );
        assertCorrupt(
            EMPTY_ROLE.replace("{\"roleId\":1,", "{\"roleId\":1,\"roleId\":1,"),
            "strict JSON"
        );
        assertCorrupt(
            EMPTY_ROLE.replace(
                "\"roleId\":1,\"name\":null",
                "\"name\":null,\"roleId\":1"
            ),
            "field order",
            "roleId"
        );
        assertCorrupt(EMPTY_ROLE + "{}", "trailing JSON content");
        assertCorrupt(" " + EMPTY_ROLE, "exact canonical encoding");
    }

    @Test
    void rejectsNonCanonicalDuplicateAndNullSetOrMapRepresentations() {
        assertCorrupt(roleJson("[\"a\",\"a\"]", "[]", "[]"), "securityPrivilegeSet", "unique");
        assertCorrupt(roleJson("[\"b\",\"a\"]", "[]", "[]"), "securityPrivilegeSet", "order");
        assertCorrupt(roleJson("[]", "[]", "[\"a\",\"a\"]"), "businessRoles", "unique");
        assertCorrupt(roleJson("[]", "[]", "[\"b\",\"a\"]"), "businessRoles", "order");
        assertCorrupt(roleJson("null", "[]", "[]"), "securityPrivilegeSet", "array");
        assertCorrupt(roleJson("[]", "null", "[]"), "resourcesMap", "array");
        assertCorrupt(roleJson("[]", "[]", "null"), "businessRoles", "array");

        String a = resourceEntry("a", emptyResource("a"));
        String b = resourceEntry("b", emptyResource("b"));
        assertCorrupt(roleJson("[]", "[" + b + "," + a + "]", "[]"), "resource-map", "order");
        assertCorrupt(roleJson("[]", "[" + a + "," + a + "]", "[]"), "resource-map", "unique");
        assertCorrupt(
            roleJson("[]", "[" + resourceEntry("a", emptyResource("b")) + "]", "[]"),
            "key must equal"
        );
        assertCorrupt(
            roleJson("[]", "[" + resourceEntry(" ", emptyResource(" ")) + "]", "[]"),
            "key",
            "blank"
        );
    }

    @Test
    void rejectsMissingUnknownOutOfOrderAndMalformedNestedResourceSchemas() {
        assertCorrupt(
            ROLE_WITH_EMPTY_RESOURCE.replace(
                "\"aggregatedExecutePrivilege\":null",
                "\"future\":null"
            ),
            "unknown field"
        );
        assertCorrupt(
            ROLE_WITH_EMPTY_RESOURCE.replace(",\"aggregatedExecutePrivilege\":null", ""),
            "missing required field aggregatedExecutePrivilege"
        );
        assertCorrupt(
            ROLE_WITH_EMPTY_RESOURCE.replace(
                "\"resourceName\":\"r\",\"privilegesList\":[]",
                "\"privilegesList\":[],\"resourceName\":\"r\""
            ),
            "field order",
            "resourceName"
        );
        assertCorrupt(
            ROLE_WITH_EMPTY_RESOURCE.replace(
                "{\"key\":\"r\",\"value\":" + EMPTY_RESOURCE,
                "{\"value\":" + EMPTY_RESOURCE + ",\"key\":\"r\""
            ),
            "field order",
            "key"
        );
        assertCorrupt(
            ROLE_WITH_EMPTY_RESOURCE.replace("\"key\":\"r\"", "\"key\":null"),
            "key",
            "non-null string"
        );
        assertCorrupt(
            ROLE_WITH_EMPTY_RESOURCE.replace("\"value\":" + EMPTY_RESOURCE, "\"value\":null"),
            "resource value object"
        );
    }

    @Test
    void rejectsMalformedAndDuplicateNestedPrivileges() {
        String resourceWithPrivilege = resourceJson("r", "[" + simplePrivilege("p", "r", "READ") + "]");
        String canonical = roleJson("[]", "[" + resourceEntry("r", resourceWithPrivilege) + "]", "[]");
        assertThat(codec.decodeRole(bytes(canonical)).resourcesMap.get("r").getPrivilegesList())
            .hasSize(1);

        assertCorrupt(
            canonical.replace("\"operation\":\"READ\"", "\"operation\":\"read\""),
            "operation",
            "enum token"
        );
        assertCorrupt(
            canonical.replace("\"name\":\"p\"", "\"future\":\"p\""),
            "unknown field"
        );

        String privilege = simplePrivilege("p", "r", "READ");
        String duplicateElements = resourceJson("r", "[" + privilege + "," + privilege + "]");
        assertCorrupt(
            roleJson("[]", "[" + resourceEntry("r", duplicateElements) + "]", "[]"),
            "duplicate elements"
        );
        String duplicateNames = resourceJson(
            "r",
            "[" + privilege + "," + simplePrivilege("p", "r", "WRITE") + "]"
        );
        assertCorrupt(
            roleJson("[]", "[" + resourceEntry("r", duplicateNames) + "]", "[]"),
            "duplicate privilege names"
        );
    }

    @Test
    void rejectsMalformedUnicodeNumbersAndAllConfiguredBounds() {
        RoleDef badName = new RoleDef(1L, "bad-\ud800");
        assertRoleEncodeFailure(badName, "name", "well-formed Unicode");
        assertRoleEncodeFailure(new RoleDef(1L, "a".repeat(256)), "name", "255");

        RoleDef tooMany = new RoleDef(1L, "r");
        for (int index = 0; index <= RedisCanonicalSnapshotPayloadCodec.MAX_COLLECTION_ENTRIES; index++) {
            tooMany.securityPrivilegeSet.add("privilege-" + index);
        }
        assertRoleEncodeFailure(tooMany, "securityPrivilegeSet", "4096");

        assertCorrupt((byte[]) null, "payload is null");
        assertCorrupt(new byte[0], "payload is empty");
        assertCorrupt(
            new byte[RedisCanonicalSnapshotPayloadCodec.MAX_ROLE_PAYLOAD_BYTES + 1],
            "exceeds",
            Integer.toString(RedisCanonicalSnapshotPayloadCodec.MAX_ROLE_PAYLOAD_BYTES)
        );
        assertCorrupt(
            EMPTY_ROLE.replace("\"name\":null", "\"name\":\"\\ud800\""),
            "name",
            "well-formed Unicode"
        );
        assertCorrupt(
            EMPTY_ROLE.replace("\"roleId\":1", "\"roleId\":9223372036854775808"),
            "strict JSON"
        );
        assertCorrupt(
            EMPTY_ROLE.replace("\"roleId\":1", "\"roleId\":-0"),
            "exact canonical encoding"
        );
        assertCorrupt(
            new byte[] { '{', '"', 'r', 'o', 'l', 'e', 'I', 'd', '"', ':', '1', ',',
                '"', 'n', 'a', 'm', 'e', '"', ':', '"', (byte) 0xc3, (byte) 0x28 },
            "strict JSON"
        );

        StringBuilder values = new StringBuilder("[");
        for (int index = 0; index <= RedisCanonicalSnapshotPayloadCodec.MAX_COLLECTION_ENTRIES; index++) {
            if (index > 0) {
                values.append(',');
            }
            values.append('"').append(String.format("p%04d", index)).append('"');
        }
        values.append(']');
        assertCorrupt(roleJson(values.toString(), "[]", "[]"), "securityPrivilegeSet", "4096");
    }

    @Test
    void hardOutputBoundAllowsExactMaximumAndRejectsTheNextByte() throws Exception {
        BoundaryFixture exact = boundaryFixture(
            RedisCanonicalSnapshotPayloadCodec.MAX_ROLE_PAYLOAD_BYTES
        );
        BoundaryFixture oneByteOver = boundaryFixture(
            RedisCanonicalSnapshotPayloadCodec.MAX_ROLE_PAYLOAD_BYTES + 1
        );

        byte[] maximum = codec.encode(exact.role);

        assertThat(maximum)
            .hasSize(RedisCanonicalSnapshotPayloadCodec.MAX_ROLE_PAYLOAD_BYTES);
        assertThat(exact.privileges.getCount()).isEqualTo(exact.privileges.size());
        assertThatThrownBy(() -> codec.encode(oneByteOver.role))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage(
                "canonical role payload exceeds " +
                    RedisCanonicalSnapshotPayloadCodec.MAX_ROLE_PAYLOAD_BYTES + " bytes"
            );
    }

    @Test
    void oversizedNestedGraphStopsAtABoundedPrefixInsteadOfDeepPrevalidatingIt()
        throws Exception {
        String resourceName = "界".repeat(255);
        int entryCount = RedisCanonicalSnapshotPayloadCodec.MAX_COLLECTION_ENTRIES;
        int[] nameLengths = new int[entryCount];
        java.util.Arrays.fill(nameLengths, 255);
        LazyPrivilegeList privileges = new LazyPrivilegeList(resourceName, nameLengths);
        RoleDef role = roleWithLazyPrivileges(resourceName, privileges);

        assertThatThrownBy(() -> codec.encode(role))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage(
                "canonical role payload exceeds " +
                    RedisCanonicalSnapshotPayloadCodec.MAX_ROLE_PAYLOAD_BYTES + " bytes"
            );
        assertThat(privileges.getCount())
            .as("only the prefix written before the hard byte bound may be traversed")
            .isPositive()
            .isLessThan(entryCount);
    }

    @Test
    void corruptionFailureUsesStableRoleCodeWithoutEchoingPayload() {
        assertThatThrownBy(() -> codec.decodeRole(bytes("secret-broken-payload")))
            .isInstanceOf(
                RedisCanonicalSnapshotPayloadCodec.CorruptRolePayloadException.class
            )
            .hasMessageStartingWith(
                RedisCanonicalSnapshotPayloadCodec.CorruptRolePayloadException.DIAGNOSTIC_CODE
            )
            .hasMessageNotContaining("secret-broken-payload");
    }

    private static RoleDef fullRole() {
        RoleDef role = new RoleDef(7L, "Odobravatelj č");
        role.securityPrivilegeSet.add("račun:čitaj");
        role.securityPrivilegeSet.add("invoice:approve");
        role.businessRoles.add("owner");
        role.businessRoles.add("auditor");

        ResourceDef resource = new ResourceDef("invoice/東京");
        PrivilegeDef read = new PrivilegeDef("invoice.read", "invoice/東京")
            .allowOperation(PrivilegeOperation.READ)
            .allowOrg(PrivilegeDirection.NONE, PrivilegeDirection.EXACT, true);
        PrivilegeDef write = new PrivilegeDef("invoice.write", "invoice/東京")
            .allowOperation(PrivilegeOperation.WRITE)
            .allowOrg(PrivilegeDirection.EXACT, PrivilegeDirection.NONE, false);
        resource.setPrivilegesList(List.of(read, write));
        resource.setAggregatedWritePrivilege(write);
        resource.setAggregatedReadPrivilege(
            new PrivilegeDef("invoice.read+write", "invoice/東京")
                .allowOperation(PrivilegeOperation.WRITE)
                .allowOrg(PrivilegeDirection.EXACT, PrivilegeDirection.NONE, false)
        );
        resource.setAggregatedExecutePrivilege(
            new PrivilegeDef("invoice.execute", "invoice/東京")
                .allowOperation(PrivilegeOperation.EXECUTE)
                .allowAll(true)
                .allowOrg(
                    PrivilegeDirection.ALL,
                    PrivilegeDirection.HIERARCHY_DOWN,
                    true
                )
        );
        role.resourcesMap.put("invoice/東京", resource);
        return role;
    }

    private static RoleDef roleWithTwoResources(boolean reverse) {
        List<String> values = reverse
            ? List.of("𐀀", "", "a")
            : List.of("a", "", "𐀀");
        RoleDef role = new RoleDef(8L, "ordering");
        role.securityPrivilegeSet = new LinkedHashSet<>(values);
        role.businessRoles = new LinkedHashSet<>(values);
        role.resourcesMap = new LinkedHashMap<>();
        for (String value : values) {
            role.resourcesMap.put(value, emptyNullableResource(value));
        }
        return role;
    }

    private static RoleDef roleWithResource(ResourceDef resource) {
        RoleDef role = new RoleDef(1L, "r");
        role.resourcesMap.put(resource.getResourceName(), resource);
        return role;
    }

    private static ResourceDef emptyNullableResource(String name) {
        ResourceDef resource = new ResourceDef(name);
        resource.setAggregatedWritePrivilege(null);
        resource.setAggregatedReadPrivilege(null);
        resource.setAggregatedExecutePrivilege(null);
        return resource;
    }

    private static PrivilegeDef privilege(
        String name,
        String resource,
        PrivilegeOperation operation
    ) {
        return new PrivilegeDef(name, resource).allowOperation(operation);
    }

    private static BoundaryFixture boundaryFixture(int encodedLength) throws Exception {
        int baseLength = bytes(ROLE_WITH_EMPTY_RESOURCE).length;
        int privilegeWithoutNameLength = bytes(simplePrivilege("", "r", "NONE")).length;
        for (
            int entryCount = 1;
            entryCount <= RedisCanonicalSnapshotPayloadCodec.MAX_COLLECTION_ENTRIES;
            entryCount++
        ) {
            int structuralLength = baseLength
                + entryCount * privilegeWithoutNameLength
                + entryCount - 1;
            int totalNameLength = encodedLength - structuralLength;
            int minimumNameLength = entryCount * LazyPrivilegeList.INDEXED_PREFIX_LENGTH;
            int maximumNameLength = entryCount
                * RedisCanonicalSnapshotPayloadCodec.MAX_STRING_UTF16_UNITS;
            if (totalNameLength < minimumNameLength || totalNameLength > maximumNameLength) {
                continue;
            }

            int[] nameLengths = new int[entryCount];
            java.util.Arrays.fill(nameLengths, LazyPrivilegeList.INDEXED_PREFIX_LENGTH);
            int remaining = totalNameLength - minimumNameLength;
            for (int index = 0; remaining > 0; index++) {
                int added = Math.min(
                    remaining,
                    RedisCanonicalSnapshotPayloadCodec.MAX_STRING_UTF16_UNITS
                        - LazyPrivilegeList.INDEXED_PREFIX_LENGTH
                );
                nameLengths[index] += added;
                remaining -= added;
            }
            LazyPrivilegeList privileges = new LazyPrivilegeList("r", nameLengths);
            return new BoundaryFixture(roleWithLazyPrivileges("r", privileges), privileges);
        }
        throw new AssertionError("cannot construct role payload of " + encodedLength + " bytes");
    }

    private static RoleDef roleWithLazyPrivileges(
        String resourceName,
        LazyPrivilegeList privileges
    ) throws Exception {
        ResourceDef resource = emptyNullableResource(resourceName);
        Field privilegesList = ResourceDef.class.getDeclaredField("privilegesList");
        privilegesList.setAccessible(true);
        privilegesList.set(resource, privileges);
        RoleDef role = new RoleDef(1L, null);
        role.resourcesMap.put(resourceName, resource);
        return role;
    }

    private static String roleJson(
        String securityPrivileges,
        String resources,
        String businessRoles
    ) {
        return "{\"roleId\":1,\"name\":null,\"securityPrivilegeSet\":" +
            securityPrivileges + ",\"resourcesMap\":" + resources +
            ",\"businessRoles\":" + businessRoles + "}";
    }

    private static String emptyResource(String name) {
        return "{\"resourceName\":\"" + name + "\",\"privilegesList\":[]," +
            "\"aggregatedWritePrivilege\":null,\"aggregatedReadPrivilege\":null," +
            "\"aggregatedExecutePrivilege\":null}";
    }

    private static String resourceJson(String name, String privileges) {
        return "{\"resourceName\":\"" + name + "\",\"privilegesList\":" +
            privileges + ",\"aggregatedWritePrivilege\":null," +
            "\"aggregatedReadPrivilege\":null,\"aggregatedExecutePrivilege\":null}";
    }

    private static String resourceEntry(String key, String resource) {
        return "{\"key\":\"" + key + "\",\"value\":" + resource + "}";
    }

    private static String simplePrivilege(String name, String resource, String operation) {
        return "{\"name\":\"" + name + "\",\"resourceName\":\"" + resource +
            "\",\"operation\":\"" + operation + "\",\"all\":false," +
            "\"company\":\"NONE\",\"org\":\"NONE\",\"person\":false}";
    }

    private static void assertPrivilege(
        PrivilegeDef privilege,
        String name,
        String resourceName,
        PrivilegeOperation operation,
        boolean all,
        PrivilegeDirection company,
        PrivilegeDirection org,
        boolean person
    ) {
        assertThat(privilege).isNotNull();
        assertThat(privilege.getClass()).isEqualTo(PrivilegeDef.class);
        assertThat(privilege.name).isEqualTo(name);
        assertThat(privilege.resourceName).isEqualTo(resourceName);
        assertThat(privilege.operation).isEqualTo(operation);
        assertThat(privilege.all).isEqualTo(all);
        assertThat(privilege.company).isEqualTo(company);
        assertThat(privilege.org).isEqualTo(org);
        assertThat(privilege.person).isEqualTo(person);
    }

    private void assertRoleEncodeFailure(RoleDef role, String... messageParts) {
        assertThatThrownBy(() -> codec.encode(role))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContainingAll(messageParts);
    }

    private void assertCorrupt(String json, String... messageParts) {
        assertCorrupt(bytes(json), messageParts);
    }

    private void assertCorrupt(byte[] payload, String... messageParts) {
        assertThatThrownBy(() -> codec.decodeRole(payload))
            .isInstanceOf(
                RedisCanonicalSnapshotPayloadCodec.CorruptRolePayloadException.class
            )
            .hasMessageStartingWith(
                RedisCanonicalSnapshotPayloadCodec.CorruptRolePayloadException.DIAGNOSTIC_CODE
            )
            .hasMessageContainingAll(messageParts);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String text(byte[] value) {
        return new String(value, StandardCharsets.UTF_8);
    }

    private static final class DerivedRoleDef extends RoleDef {
        private DerivedRoleDef() {
            super(1L, "derived");
        }
    }

    private static final class DerivedResourceDef extends ResourceDef {
        private DerivedResourceDef() {
            super("r");
        }
    }

    private static final class DerivedPrivilegeDef extends PrivilegeDef {}

    private static final class BoundaryFixture {
        private final RoleDef role;
        private final LazyPrivilegeList privileges;

        private BoundaryFixture(RoleDef role, LazyPrivilegeList privileges) {
            this.role = role;
            this.privileges = privileges;
        }
    }

    /** Lazily materializes only those privileges the streaming encoder actually traverses. */
    private static final class LazyPrivilegeList extends AbstractList<PrivilegeDef> {

        private static final int INDEXED_PREFIX_LENGTH = 5;

        private final String resourceName;
        private final int[] nameLengths;
        private int getCount;

        private LazyPrivilegeList(String resourceName, int[] nameLengths) {
            this.resourceName = resourceName;
            this.nameLengths = nameLengths.clone();
        }

        @Override
        public PrivilegeDef get(int index) {
            getCount++;
            return new PrivilegeDef(indexedName(index, nameLengths[index]), resourceName);
        }

        @Override
        public int size() {
            return nameLengths.length;
        }

        private int getCount() {
            return getCount;
        }

        private static String indexedName(int index, int length) {
            String digits = Integer.toString(index);
            String prefix = "p" + "0".repeat(4 - digits.length()) + digits;
            if (prefix.length() != INDEXED_PREFIX_LENGTH || length < prefix.length()) {
                throw new AssertionError("invalid indexed privilege name length");
            }
            return prefix + "x".repeat(length - prefix.length());
        }
    }
}
