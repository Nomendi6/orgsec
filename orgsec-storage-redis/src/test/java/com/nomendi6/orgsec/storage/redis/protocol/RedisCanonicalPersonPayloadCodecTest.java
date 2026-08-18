package com.nomendi6.orgsec.storage.redis.protocol;

import com.nomendi6.orgsec.model.BusinessRoleDef;
import com.nomendi6.orgsec.model.OrganizationDef;
import com.nomendi6.orgsec.model.PersonDef;
import com.nomendi6.orgsec.model.PrivilegeDef;
import com.nomendi6.orgsec.model.ResourceDef;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.AbstractList;
import java.util.LinkedHashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RedisCanonicalPersonPayloadCodecTest {

    private static final String EMPTY_ORGANIZATION =
        "{\"organizationId\":10,\"organizationName\":\"Odjel č\"," +
        "\"positionId\":null,\"pathId\":null,\"parentPath\":null," +
        "\"companyId\":null,\"companyParentPath\":null," +
        "\"positionRolesSet\":[],\"organizationRolesSet\":[]," +
        "\"businessRolesMap\":[]}";

    private static final String FULL_GOLDEN =
        "{\"personId\":5,\"personName\":\"Ana 😀\",\"defaultCompanyId\":1," +
        "\"defaultOrgunitId\":10,\"relatedUserId\":\"u-5\"," +
        "\"relatedUserLogin\":\"ana\",\"organizationsMap\":[{" +
        "\"key\":\"10\",\"value\":" + EMPTY_ORGANIZATION + "}]}";

    private final RedisCanonicalSnapshotPayloadCodec codec =
        new RedisCanonicalSnapshotPayloadCodec();

    @Test
    void hasStableGoldenBytesAndRoundTripsExactBaseGraph() {
        PersonDef person = new PersonDef(5L, "Ana 😀");
        person.defaultCompanyId = 1L;
        person.defaultOrgunitId = 10L;
        person.relatedUserId = "u-5";
        person.relatedUserLogin = "ana";
        OrganizationDef organization = organization(10L);
        organization.organizationName = "Odjel č";
        person.organizationsMap.put(10L, organization);

        byte[] encoded = codec.encode(person);
        PersonDef decoded = codec.decodePerson(encoded);

        assertThat(encoded).containsExactly(FULL_GOLDEN.getBytes(StandardCharsets.UTF_8));
        assertThat(decoded.getClass()).isEqualTo(PersonDef.class);
        assertThat(decoded.personId).isEqualTo(5L);
        assertThat(decoded.personName).isEqualTo("Ana 😀");
        assertThat(decoded.defaultCompanyId).isEqualTo(1L);
        assertThat(decoded.defaultOrgunitId).isEqualTo(10L);
        assertThat(decoded.relatedUserId).isEqualTo("u-5");
        assertThat(decoded.relatedUserLogin).isEqualTo("ana");
        assertThat(decoded.organizationsMap).containsOnlyKeys(10L);
        assertThat(decoded.organizationsMap.get(10L).getClass())
            .isEqualTo(OrganizationDef.class);
    }

    @Test
    void writesNullableScalarsAsExplicitNullAndRequiresOrganizationsMap() {
        PersonDef person = person(1L);

        String encoded = text(codec.encode(person));

        assertThat(encoded).isEqualTo(
            "{\"personId\":1,\"personName\":null,\"defaultCompanyId\":null," +
            "\"defaultOrgunitId\":null,\"relatedUserId\":null," +
            "\"relatedUserLogin\":null,\"organizationsMap\":[]}"
        );
        assertThat(codec.decodePerson(bytes(encoded))).isEqualTo(person);

        person.organizationsMap = null;
        assertEncodeFailure(person, "organizationsMap", "must not be null");
    }

    @Test
    void canonicalizesNumericMapKeysByUnsignedUtf8DecimalTokens() {
        PersonDef forward = personWithOrganizationOrder(false);
        PersonDef reverse = personWithOrganizationOrder(true);

        byte[] forwardBytes = codec.encode(forward);
        byte[] reverseBytes = codec.encode(reverse);

        assertThat(reverseBytes).containsExactly(forwardBytes);
        String json = text(forwardBytes);
        int negative = json.indexOf("\"key\":\"-1\"");
        int ten = json.indexOf("\"key\":\"10\"");
        int two = json.indexOf("\"key\":\"2\"");
        assertThat(negative).isLessThan(ten);
        assertThat(ten).isLessThan(two);
    }

    @Test
    void rejectsNonCanonicalSignedDecimalMapKeysAndOverflow() {
        String oneOrganization = text(codec.encode(personWithSingleOrganization(1L)));

        assertCorrupt(oneOrganization.replace("\"key\":\"1\"", "\"key\":\"+1\""), "canonical", "decimal");
        assertCorrupt(oneOrganization.replace("\"key\":\"1\"", "\"key\":\"01\""), "canonical", "decimal");
        assertCorrupt(oneOrganization.replace("\"key\":\"1\"", "\"key\":\"-0\""), "canonical", "decimal");
        assertCorrupt(
            oneOrganization.replace(
                "\"key\":\"1\"",
                "\"key\":\"9223372036854775808\""
            ),
            "canonical",
            "64-bit"
        );

        PersonDef extremes = person(1L);
        extremes.organizationsMap.put(Long.MIN_VALUE, organization(Long.MIN_VALUE));
        extremes.organizationsMap.put(Long.MAX_VALUE, organization(Long.MAX_VALUE));
        assertThat(codec.decodePerson(codec.encode(extremes)).organizationsMap)
            .containsOnlyKeys(Long.MIN_VALUE, Long.MAX_VALUE);
        assertCorrupt(
            oneOrganization.replace(
                "\"key\":\"1\"",
                "\"key\":\"-9223372036854775809\""
            ),
            "canonical",
            "64-bit"
        );
    }

    @Test
    void rejectsOrganizationMapIdentityMismatchAndNonCanonicalOrder() {
        PersonDef person = person(1L);
        person.organizationsMap.put(1L, organization(2L));
        assertEncodeFailure(
            person,
            "organization-map key",
            "OrganizationDef.organizationId"
        );

        String ten = organizationEntry(10L);
        String two = organizationEntry(2L);
        assertCorrupt(
            emptyPersonJson().replace(
                "\"organizationsMap\":[]",
                "\"organizationsMap\":[" + two + "," + ten + "]"
            ),
            "organizationsMap keys",
            "canonical unsigned UTF-8 order"
        );
        assertCorrupt(
            emptyPersonJson().replace(
                "\"organizationsMap\":[]",
                "\"organizationsMap\":[{\"key\":\"1\",\"value\":" +
                    organizationJson(2L) + "}]"
            ),
            "organization-map key",
            "organizationId"
        );
    }

    @Test
    void rejectsPersonAndEmbeddedOrganizationRuntimeSubtypes() {
        assertEncodeFailure(new DerivedPersonDef(), "exact PersonDef class");

        PersonDef person = person(1L);
        person.organizationsMap.put(1L, new DerivedOrganizationDef());
        assertEncodeFailure(person, "exact OrganizationDef class");
    }

    @Test
    void rejectsMalformedWrongKindUnknownOrderAndNonCanonicalBytes() {
        String empty = emptyPersonJson();
        assertCorrupt(
            empty.replace(
                "\"personId\":1,\"personName\":null",
                "\"personName\":null,\"personId\":1"
            ),
            "field order",
            "personId"
        );
        assertCorrupt(empty.replace("\"personName\":null", "\"x\":null"), "unknown field");
        assertCorrupt(
            empty.replace("\"defaultCompanyId\":null", "\"defaultCompanyId\":\"1\""),
            "defaultCompanyId",
            "64-bit"
        );
        assertCorrupt("\n" + empty, "canonical encoding");
        assertCorrupt(bytes("{"), "strict JSON");

        byte[] oversized = new byte[
            RedisCanonicalSnapshotPayloadCodec.MAX_PERSON_PAYLOAD_BYTES + 1
        ];
        assertCorrupt(oversized, "payload exceeds", "4194304");
    }

    @Test
    void acceptsExactPersonOutputLimitAndRejectsTheNextByte() {
        PersonDef person = filterBoundaryPerson(63);
        BusinessRoleDef tail = person.organizationsMap.get(1L)
            .businessRolesMap.get("zz-tail");
        int remaining = RedisCanonicalSnapshotPayloadCodec.MAX_PERSON_PAYLOAD_BYTES
            - codec.encode(person).length;
        assertThat(remaining).isBetween(
            1,
            RedisCanonicalSnapshotPayloadCodec.MAX_FILTER_UTF8_BYTES - 2
        );
        // Replacing JSON null (4 bytes) with quoted text adds textLength - 2 bytes.
        tail.filter = "x".repeat(remaining + 2);

        byte[] exact = codec.encode(person);
        assertThat(exact).hasSize(RedisCanonicalSnapshotPayloadCodec.MAX_PERSON_PAYLOAD_BYTES);
        assertThat(codec.decodePerson(exact).personId).isEqualTo(1L);

        tail.filter += "x";
        assertEncodeFailure(
            person,
            "canonical person payload exceeds",
            Integer.toString(RedisCanonicalSnapshotPayloadCodec.MAX_PERSON_PAYLOAD_BYTES)
        );
    }

    @Test
    void outputLimitStopsNestedLazyTraversalBeforeUnreachableSuffix() throws Exception {
        LazyPrivilegeList first = new LazyPrivilegeList("a".repeat(255), 4096);
        LazyPrivilegeList second = new LazyPrivilegeList("b".repeat(255), 4096);
        LazyPrivilegeList unreachable = new LazyPrivilegeList("c".repeat(255), 4096);
        PersonDef person = personWithLazyResources(first, second, unreachable);

        assertEncodeFailure(person, "canonical person payload exceeds");
        assertThat(first.getCount()).isEqualTo(first.size());
        assertThat(second.getCount()).isPositive().isLessThan(second.size());
        assertThat(unreachable.getCount()).isZero();
    }

    @Test
    void organizationsMapAccepts4096EntriesAndRejects4097() {
        PersonDef person = person(1L);
        for (
            long id = 0;
            id < RedisCanonicalSnapshotPayloadCodec.MAX_COLLECTION_ENTRIES;
            id++
        ) {
            person.organizationsMap.put(id, organization(id));
        }

        assertThat(codec.decodePerson(codec.encode(person)).organizationsMap)
            .hasSize(RedisCanonicalSnapshotPayloadCodec.MAX_COLLECTION_ENTRIES);

        long overflow = RedisCanonicalSnapshotPayloadCodec.MAX_COLLECTION_ENTRIES;
        person.organizationsMap.put(overflow, organization(overflow));
        assertEncodeFailure(person, "organizationsMap", "4096");
    }

    private static PersonDef person(long id) {
        PersonDef person = new PersonDef(id, null);
        person.organizationsMap = new LinkedHashMap<>();
        return person;
    }

    private static OrganizationDef organization(long id) {
        OrganizationDef organization = new OrganizationDef();
        organization.organizationId = id;
        organization.positionRolesSet = new java.util.LinkedHashSet<>();
        organization.organizationRolesSet = new java.util.LinkedHashSet<>();
        organization.businessRolesMap = new LinkedHashMap<>();
        return organization;
    }

    private static BusinessRoleDef businessRole(String name) {
        BusinessRoleDef businessRole = new BusinessRoleDef(name);
        businessRole.resourcesMap = new LinkedHashMap<>();
        return businessRole;
    }

    private static PersonDef personWithOrganizationOrder(boolean reverse) {
        PersonDef person = person(1L);
        List<Long> ids = reverse ? List.of(2L, 10L, -1L) : List.of(-1L, 10L, 2L);
        for (Long id : ids) {
            person.organizationsMap.put(id, organization(id));
        }
        return person;
    }

    private static PersonDef personWithSingleOrganization(long organizationId) {
        PersonDef person = person(1L);
        person.organizationsMap.put(organizationId, organization(organizationId));
        return person;
    }

    private static PersonDef filterBoundaryPerson(int fullFilterCount) {
        PersonDef person = personWithSingleOrganization(1L);
        OrganizationDef organization = person.organizationsMap.get(1L);
        for (int index = 0; index < fullFilterCount; index++) {
            String name = "b" + (index < 10 ? "0" : "") + index;
            BusinessRoleDef businessRole = businessRole(name);
            businessRole.filter = "x".repeat(
                RedisCanonicalSnapshotPayloadCodec.MAX_FILTER_UTF8_BYTES
            );
            organization.businessRolesMap.put(name, businessRole);
        }
        organization.businessRolesMap.put("zz-tail", businessRole("zz-tail"));
        return person;
    }

    private static PersonDef personWithLazyResources(LazyPrivilegeList... lists)
        throws Exception {
        PersonDef person = personWithSingleOrganization(1L);
        BusinessRoleDef businessRole = businessRole("owner");
        Field field = ResourceDef.class.getDeclaredField("privilegesList");
        field.setAccessible(true);
        for (LazyPrivilegeList list : lists) {
            ResourceDef resource = new ResourceDef(list.resourceName);
            resource.setAggregatedWritePrivilege(null);
            resource.setAggregatedReadPrivilege(null);
            resource.setAggregatedExecutePrivilege(null);
            field.set(resource, list);
            businessRole.resourcesMap.put(list.resourceName, resource);
        }
        person.organizationsMap.get(1L).businessRolesMap.put("owner", businessRole);
        return person;
    }

    private static String emptyPersonJson() {
        return text(new RedisCanonicalSnapshotPayloadCodec().encode(person(1L)));
    }

    private static String organizationEntry(long id) {
        return "{\"key\":\"" + id + "\",\"value\":" + organizationJson(id) + "}";
    }

    private static String organizationJson(long id) {
        return "{\"organizationId\":" + id + ",\"organizationName\":null," +
            "\"positionId\":null,\"pathId\":null,\"parentPath\":null," +
            "\"companyId\":null,\"companyParentPath\":null," +
            "\"positionRolesSet\":[],\"organizationRolesSet\":[]," +
            "\"businessRolesMap\":[]}";
    }

    private void assertEncodeFailure(PersonDef person, String... fragments) {
        assertThatThrownBy(() -> codec.encode(person))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContainingAll(fragments);
    }

    private void assertCorrupt(String json, String... fragments) {
        assertCorrupt(bytes(json), fragments);
    }

    private void assertCorrupt(byte[] payload, String... fragments) {
        assertThatThrownBy(() -> codec.decodePerson(payload))
            .isInstanceOf(
                RedisCanonicalSnapshotPayloadCodec.CorruptPersonPayloadException.class
            )
            .hasMessageStartingWith(
                RedisCanonicalSnapshotPayloadCodec.CorruptPersonPayloadException.DIAGNOSTIC_CODE
            )
            .hasMessageContainingAll(fragments);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String text(byte[] value) {
        return new String(value, StandardCharsets.UTF_8);
    }

    private static final class DerivedPersonDef extends PersonDef {
        private DerivedPersonDef() {
            super(1L, "derived");
        }
    }

    private static final class DerivedOrganizationDef extends OrganizationDef {
        private DerivedOrganizationDef() {
            organizationId = 1L;
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
            String prefix = "p" + "0".repeat(4 - Integer.toString(index).length()) + index;
            return new PrivilegeDef(prefix + "x".repeat(250), resourceName);
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
