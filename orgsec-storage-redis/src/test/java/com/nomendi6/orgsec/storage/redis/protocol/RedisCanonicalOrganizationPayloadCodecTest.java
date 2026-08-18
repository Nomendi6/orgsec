package com.nomendi6.orgsec.storage.redis.protocol;

import com.nomendi6.orgsec.model.BusinessRoleDef;
import com.nomendi6.orgsec.model.OrganizationDef;
import com.nomendi6.orgsec.model.PrivilegeDef;
import com.nomendi6.orgsec.model.ResourceDef;
import com.nomendi6.orgsec.model.RoleDef;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.AbstractList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RedisCanonicalOrganizationPayloadCodecTest {

    private static final String EMPTY_ROLE =
        "{\"roleId\":2,\"name\":\"Čuvar\",\"securityPrivilegeSet\":[]," +
        "\"resourcesMap\":[],\"businessRoles\":[]}";

    private static final String EMPTY_RESOURCE =
        "{\"resourceName\":\"invoice\",\"privilegesList\":[]," +
        "\"aggregatedWritePrivilege\":null,\"aggregatedReadPrivilege\":null," +
        "\"aggregatedExecutePrivilege\":null}";

    private static final String FULL_GOLDEN =
        "{\"organizationId\":10,\"organizationName\":\"Tvrtka č\"," +
        "\"positionId\":20,\"pathId\":\"|1|10|\",\"parentPath\":null," +
        "\"companyId\":1,\"companyParentPath\":\"|1|\"," +
        "\"positionRolesSet\":[" + EMPTY_ROLE + "],\"organizationRolesSet\":[]," +
        "\"businessRolesMap\":[{\"key\":\"owner\",\"value\":{" +
        "\"businessRoleName\":\"owner\",\"resourcesMap\":[{" +
        "\"key\":\"invoice\",\"value\":" + EMPTY_RESOURCE + "}]," +
        "\"filter\":\"status==ACTIVE\",\"allowAll\":false}}]," +
        "\"parentId\":null,\"orgLineageIds\":null,\"companyLineageIds\":null}";

    private final RedisCanonicalSnapshotPayloadCodec codec =
        new RedisCanonicalSnapshotPayloadCodec();

    @Test
    void hasStableGoldenBytesAndRoundTripsExactBaseGraph() {
        OrganizationDef organization = fullOrganization();

        byte[] encoded = codec.encode(organization);
        OrganizationDef decoded = codec.decodeOrganization(encoded);

        assertThat(encoded).containsExactly(FULL_GOLDEN.getBytes(StandardCharsets.UTF_8));
        assertThat(decoded.getClass()).isEqualTo(OrganizationDef.class);
        assertThat(decoded.organizationId).isEqualTo(10L);
        assertThat(decoded.organizationName).isEqualTo("Tvrtka č");
        assertThat(decoded.positionId).isEqualTo(20L);
        assertThat(decoded.pathId).isEqualTo("|1|10|");
        assertThat(decoded.parentPath).isNull();
        assertThat(decoded.companyId).isEqualTo(1L);
        assertThat(decoded.companyParentPath).isEqualTo("|1|");
        assertThat(decoded.parentId).isNull();
        assertThat(decoded.orgLineageIds).isNull();
        assertThat(decoded.companyLineageIds).isNull();
        assertThat(decoded.positionRolesSet).singleElement().satisfies(role -> {
            assertThat(role.getClass()).isEqualTo(RoleDef.class);
            assertThat(role.roleId).isEqualTo(2L);
            assertThat(role.name).isEqualTo("Čuvar");
        });
        assertThat(decoded.organizationRolesSet).isEmpty();
        assertThat(decoded.businessRolesMap).containsOnlyKeys("owner");
        BusinessRoleDef businessRole = decoded.businessRolesMap.get("owner");
        assertThat(businessRole.getClass()).isEqualTo(BusinessRoleDef.class);
        assertThat(businessRole.filter).isEqualTo("status==ACTIVE");
        assertThat(businessRole.allowAll).isFalse();
        assertThat(businessRole.resourcesMap.get("invoice").getClass())
            .isEqualTo(ResourceDef.class);
    }

    @Test
    void writesAllNullableScalarsAsExplicitNullAndRequiresCollections() {
        OrganizationDef organization = organization(1L);

        String encoded = text(codec.encode(organization));

        assertThat(encoded).isEqualTo(
            "{\"organizationId\":1,\"organizationName\":null,\"positionId\":null," +
            "\"pathId\":null,\"parentPath\":null,\"companyId\":null," +
            "\"companyParentPath\":null,\"positionRolesSet\":[]," +
            "\"organizationRolesSet\":[],\"businessRolesMap\":[]," +
            "\"parentId\":null,\"orgLineageIds\":null,\"companyLineageIds\":null}"
        );
        assertThat(codec.decodeOrganization(bytes(encoded))).isEqualTo(organization);

        organization.positionRolesSet = null;
        assertEncodeFailure(organization, "positionRolesSet", "must not be null");
        organization.positionRolesSet = new LinkedHashSet<>();
        organization.organizationRolesSet = null;
        assertEncodeFailure(organization, "organizationRolesSet", "must not be null");
        organization.organizationRolesSet = new LinkedHashSet<>();
        organization.businessRolesMap = null;
        assertEncodeFailure(organization, "businessRolesMap", "must not be null");
    }

    @Test
    void canonicalizesRoleSetsAndMapsByUnsignedUtf8Identity() {
        OrganizationDef forward = orderedOrganization(false);
        OrganizationDef reverse = orderedOrganization(true);

        byte[] forwardBytes = codec.encode(forward);
        byte[] reverseBytes = codec.encode(reverse);

        assertThat(reverseBytes).containsExactly(forwardBytes);
        assertThat(codec.decodeOrganization(forwardBytes).positionRolesSet)
            .extracting(role -> role.roleId)
            .containsExactlyInAnyOrder(-1L, 10L, 2L);
        String json = text(forwardBytes);
        assertThat(json).contains(
            "\"positionRolesSet\":[{\"roleId\":-1",
            "},{\"roleId\":10",
            "},{\"roleId\":2"
        );
        assertThat(json.indexOf("\"key\":\"a\""))
            .isLessThan(json.indexOf("\"key\":\"\""));
        assertThat(json.indexOf("\"key\":\"\""))
            .isLessThan(json.indexOf("\"key\":\"𐀀\""));
    }

    @Test
    void rejectsDuplicateRoleIdentityAndMapIdentityMismatchOnEncodeAndDecode() {
        OrganizationDef organization = organization(1L);
        organization.positionRolesSet = new LinkedHashSet<>(List.of(
            new RoleDef(7L, "first"),
            new RoleDef(7L, "second")
        ));
        assertEncodeFailure(organization, "positionRolesSet roleIds", "unique");

        organization = organization(1L);
        BusinessRoleDef owner = businessRole("embedded");
        organization.businessRolesMap.put("map-key", owner);
        assertEncodeFailure(
            organization,
            "business-role-map key",
            "BusinessRoleDef.businessRoleName"
        );

        String first = emptyRole(7L, "first");
        String second = emptyRole(7L, "second");
        assertCorrupt(
            emptyOrganizationJson().replace(
                "\"positionRolesSet\":[]",
                "\"positionRolesSet\":[" + first + "," + second + "]"
            ),
            "positionRolesSet roleIds",
            "unique"
        );
        assertCorrupt(
            emptyOrganizationJson().replace(
                "\"businessRolesMap\":[]",
                "\"businessRolesMap\":[{\"key\":\"wrong\",\"value\":" +
                    emptyBusinessRole("embedded") + "}]"
            ),
            "business-role-map key",
            "businessRoleName"
        );
    }

    @Test
    void rejectsEveryRuntimeSubtypeOnEncode() {
        assertEncodeFailure(new DerivedOrganizationDef(), "exact OrganizationDef class");

        OrganizationDef organization = organization(1L);
        organization.positionRolesSet.add(new DerivedRoleDef());
        assertEncodeFailure(organization, "exact RoleDef class");

        organization = organization(1L);
        organization.businessRolesMap.put("owner", new DerivedBusinessRoleDef());
        assertEncodeFailure(organization, "exact BusinessRoleDef class");

        organization = organization(1L);
        BusinessRoleDef businessRole = businessRole("owner");
        businessRole.resourcesMap.put("r", new DerivedResourceDef());
        organization.businessRolesMap.put("owner", businessRole);
        assertEncodeFailure(organization, "exact ResourceDef class");
    }

    @Test
    void supportsPathWireBoundaryIncludingSupplementaryUnicode() {
        OrganizationDef organization = organization(1L);
        organization.pathId = "ࠀ".repeat(RedisCanonicalSnapshotPayloadCodec.MAX_PATH_UTF16_UNITS);
        organization.parentPath = "😀".repeat(
            RedisCanonicalSnapshotPayloadCodec.MAX_PATH_UTF16_UNITS / 2
        );

        OrganizationDef decoded = codec.decodeOrganization(codec.encode(organization));

        assertThat(decoded.pathId.getBytes(StandardCharsets.UTF_8)).hasSize(
            RedisCanonicalSnapshotPayloadCodec.MAX_PATH_UTF8_BYTES
        );
        assertThat(decoded.parentPath).hasSize(
            RedisCanonicalSnapshotPayloadCodec.MAX_PATH_UTF16_UNITS
        );

        organization.pathId = "x".repeat(
            RedisCanonicalSnapshotPayloadCodec.MAX_PATH_UTF16_UNITS + 1
        );
        assertEncodeFailure(organization, "pathId", "1000 UTF-16");

        assertCorrupt(
            emptyOrganizationJson().replace(
                "\"pathId\":null",
                "\"pathId\":\"" + "x".repeat(1001) + "\""
            ),
            "pathId",
            "1000 UTF-16"
        );
    }

    @Test
    void enforcesExplicitBusinessRoleFilterWireBoundary() {
        OrganizationDef organization = organizationWithFilter(
            "x".repeat(RedisCanonicalSnapshotPayloadCodec.MAX_FILTER_UTF8_BYTES)
        );
        assertThat(codec.decodeOrganization(codec.encode(organization))
            .businessRolesMap.get("owner").filter).hasSize(
                RedisCanonicalSnapshotPayloadCodec.MAX_FILTER_UTF8_BYTES
            );

        organization.businessRolesMap.get("owner").filter = "x".repeat(
            RedisCanonicalSnapshotPayloadCodec.MAX_FILTER_UTF8_BYTES + 1
        );
        assertEncodeFailure(organization, "filter", "65536 UTF-16");

        organization.businessRolesMap.get("owner").filter = "ࠀ".repeat(21_846);
        assertEncodeFailure(organization, "filter", "65536 UTF-8");

        assertCorrupt(
            text(codec.encode(organizationWithFilter(null))).replace(
                "\"filter\":null",
                "\"filter\":\"" + "ࠀ".repeat(21_846) + "\""
            ),
            "filter",
            "65536 UTF-8"
        );
    }

    @Test
    void rejectsMalformedOutOfOrderUnknownAndNonCanonicalPayloadBytes() {
        String empty = emptyOrganizationJson();
        assertCorrupt(
            empty.replace(
                "\"organizationId\":1,\"organizationName\":null",
                "\"organizationName\":null,\"organizationId\":1"
            ),
            "field order",
            "organizationId"
        );
        assertCorrupt(empty.replace("\"organizationName\":null", "\"x\":null"), "unknown field");
        assertCorrupt(empty.replace("\"positionId\":null", "\"positionId\":\"1\""), "positionId");
        assertCorrupt(" " + empty, "canonical encoding");
        assertCorrupt(bytes("{"), "strict JSON");
    }

    @Test
    void acceptsExactOrganizationOutputLimitAndRejectsTheNextByte() {
        OrganizationDef organization = filterBoundaryOrganization(31);
        BusinessRoleDef tail = organization.businessRolesMap.get("zz-tail");
        int remaining = RedisCanonicalSnapshotPayloadCodec.MAX_ORGANIZATION_PAYLOAD_BYTES
            - codec.encode(organization).length;
        assertThat(remaining).isBetween(
            1,
            RedisCanonicalSnapshotPayloadCodec.MAX_FILTER_UTF8_BYTES - 2
        );
        // Replacing JSON null (4 bytes) with quoted text adds textLength - 2 bytes.
        tail.filter = "x".repeat(remaining + 2);

        byte[] exact = codec.encode(organization);
        assertThat(exact).hasSize(
            RedisCanonicalSnapshotPayloadCodec.MAX_ORGANIZATION_PAYLOAD_BYTES
        );
        assertThat(codec.decodeOrganization(exact).organizationId).isEqualTo(1L);

        tail.filter += "x";
        assertEncodeFailure(
            organization,
            "canonical organization payload exceeds",
            Integer.toString(RedisCanonicalSnapshotPayloadCodec.MAX_ORGANIZATION_PAYLOAD_BYTES)
        );

        assertCorrupt(
            new byte[RedisCanonicalSnapshotPayloadCodec.MAX_ORGANIZATION_PAYLOAD_BYTES + 1],
            "payload exceeds",
            "2097152"
        );
    }

    @Test
    void outputLimitStopsADeepLazyListBeforeItsSuffixIsMaterialized() throws Exception {
        LazyPrivilegeList privileges = new LazyPrivilegeList("r".repeat(255), 4096);
        OrganizationDef organization = organizationWithLazyPrivileges(privileges);

        assertEncodeFailure(organization, "canonical organization payload exceeds");
        assertThat(privileges.getCount()).isPositive();
        assertThat(privileges.getCount()).isLessThan(privileges.size());
    }

    @Test
    void businessRoleMapAccepts4096EntriesAndRejects4097() {
        OrganizationDef organization = organization(1L);
        for (
            int index = 0;
            index < RedisCanonicalSnapshotPayloadCodec.MAX_COLLECTION_ENTRIES;
            index++
        ) {
            String name = "b" + index;
            organization.businessRolesMap.put(name, businessRole(name));
        }

        assertThat(codec.decodeOrganization(codec.encode(organization)).businessRolesMap)
            .hasSize(RedisCanonicalSnapshotPayloadCodec.MAX_COLLECTION_ENTRIES);

        organization.businessRolesMap.put("overflow", businessRole("overflow"));
        assertEncodeFailure(organization, "businessRolesMap", "4096");
    }

    private static OrganizationDef fullOrganization() {
        OrganizationDef organization = organization(10L);
        organization.organizationName = "Tvrtka č";
        organization.positionId = 20L;
        organization.pathId = "|1|10|";
        organization.companyId = 1L;
        organization.companyParentPath = "|1|";
        organization.positionRolesSet.add(new RoleDef(2L, "Čuvar"));
        BusinessRoleDef owner = businessRole("owner");
        owner.filter = "status==ACTIVE";
        owner.resourcesMap.put("invoice", emptyResource("invoice"));
        organization.businessRolesMap.put("owner", owner);
        return organization;
    }

    private static OrganizationDef orderedOrganization(boolean reverse) {
        OrganizationDef organization = organization(1L);
        List<Long> roleIds = reverse ? List.of(2L, 10L, -1L) : List.of(-1L, 10L, 2L);
        for (Long roleId : roleIds) {
            organization.positionRolesSet.add(new RoleDef(roleId, "r" + roleId));
        }
        List<String> names = reverse
            ? List.of("𐀀", "", "a")
            : List.of("a", "", "𐀀");
        organization.businessRolesMap = new LinkedHashMap<>();
        for (String name : names) {
            organization.businessRolesMap.put(name, businessRole(name));
        }
        return organization;
    }

    private static OrganizationDef organization(long id) {
        OrganizationDef organization = new OrganizationDef();
        organization.organizationId = id;
        organization.positionRolesSet = new LinkedHashSet<>();
        organization.organizationRolesSet = new LinkedHashSet<>();
        organization.businessRolesMap = new LinkedHashMap<>();
        return organization;
    }

    private static BusinessRoleDef businessRole(String name) {
        BusinessRoleDef businessRole = new BusinessRoleDef(name);
        businessRole.resourcesMap = new LinkedHashMap<>();
        return businessRole;
    }

    private static ResourceDef emptyResource(String name) {
        ResourceDef resource = new ResourceDef(name);
        resource.setAggregatedWritePrivilege(null);
        resource.setAggregatedReadPrivilege(null);
        resource.setAggregatedExecutePrivilege(null);
        return resource;
    }

    private static OrganizationDef organizationWithFilter(String filter) {
        OrganizationDef organization = organization(1L);
        BusinessRoleDef owner = businessRole("owner");
        owner.filter = filter;
        organization.businessRolesMap.put("owner", owner);
        return organization;
    }

    private static OrganizationDef filterBoundaryOrganization(int fullFilterCount) {
        OrganizationDef organization = organization(1L);
        for (int index = 0; index < fullFilterCount; index++) {
            String name = "b" + String.format("%02d", index);
            BusinessRoleDef role = businessRole(name);
            role.filter = "x".repeat(RedisCanonicalSnapshotPayloadCodec.MAX_FILTER_UTF8_BYTES);
            organization.businessRolesMap.put(name, role);
        }
        organization.businessRolesMap.put("zz-tail", businessRole("zz-tail"));
        return organization;
    }

    private static OrganizationDef organizationWithLazyPrivileges(
        LazyPrivilegeList privileges
    ) throws Exception {
        OrganizationDef organization = organization(1L);
        BusinessRoleDef businessRole = businessRole("owner");
        ResourceDef resource = emptyResource(privileges.resourceName);
        Field field = ResourceDef.class.getDeclaredField("privilegesList");
        field.setAccessible(true);
        field.set(resource, privileges);
        businessRole.resourcesMap.put(privileges.resourceName, resource);
        organization.businessRolesMap.put("owner", businessRole);
        return organization;
    }

    private static String emptyOrganizationJson() {
        return text(new RedisCanonicalSnapshotPayloadCodec().encode(organization(1L)));
    }

    private static String emptyRole(long id, String name) {
        return "{\"roleId\":" + id + ",\"name\":\"" + name +
            "\",\"securityPrivilegeSet\":[],\"resourcesMap\":[]," +
            "\"businessRoles\":[]}";
    }

    private static String emptyBusinessRole(String name) {
        return "{\"businessRoleName\":\"" + name +
            "\",\"resourcesMap\":[],\"filter\":null,\"allowAll\":false}";
    }

    private void assertEncodeFailure(OrganizationDef organization, String... fragments) {
        assertThatThrownBy(() -> codec.encode(organization))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContainingAll(fragments);
    }

    private void assertCorrupt(String json, String... fragments) {
        assertCorrupt(bytes(json), fragments);
    }

    private void assertCorrupt(byte[] payload, String... fragments) {
        assertThatThrownBy(() -> codec.decodeOrganization(payload))
            .isInstanceOf(
                RedisCanonicalSnapshotPayloadCodec.CorruptOrganizationPayloadException.class
            )
            .hasMessageStartingWith(
                RedisCanonicalSnapshotPayloadCodec.CorruptOrganizationPayloadException
                    .DIAGNOSTIC_CODE
            )
            .hasMessageContainingAll(fragments);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String text(byte[] value) {
        return new String(value, StandardCharsets.UTF_8);
    }

    private static final class DerivedOrganizationDef extends OrganizationDef {}

    private static final class DerivedRoleDef extends RoleDef {
        private DerivedRoleDef() {
            super(1L, "derived");
        }
    }

    private static final class DerivedBusinessRoleDef extends BusinessRoleDef {
        private DerivedBusinessRoleDef() {
            super("owner");
        }
    }

    private static final class DerivedResourceDef extends ResourceDef {
        private DerivedResourceDef() {
            super("r");
        }
    }

    /** Lazily materializes only the privilege prefix reached by the bounded writer. */
    private static final class LazyPrivilegeList extends AbstractList<PrivilegeDef> {
        private final String resourceName;
        private final int size;
        private int getCount;

        private LazyPrivilegeList(String resourceName, int size) {
            this.resourceName = resourceName;
            this.size = size;
        }

        @Override
        public PrivilegeDef get(int index) {
            getCount++;
            String prefix = String.format("p%04d", index);
            return new PrivilegeDef(prefix + "x".repeat(250), resourceName)
                .allowOperation(com.nomendi6.orgsec.constants.PrivilegeOperation.NONE);
        }

        @Override
        public int size() {
            return size;
        }

        private int getCount() {
            return getCount;
        }
    }
}
