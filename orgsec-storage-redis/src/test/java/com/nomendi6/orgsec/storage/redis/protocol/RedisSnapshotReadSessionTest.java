package com.nomendi6.orgsec.storage.redis.protocol;

import com.nomendi6.orgsec.fence.SecurityDatasetFence;
import com.nomendi6.orgsec.fence.SecurityDatasetIdentity;
import com.nomendi6.orgsec.model.PersonDef;
import com.nomendi6.orgsec.storage.redis.bootstrap.RedisSnapshotFamily;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class RedisSnapshotReadSessionTest {

    private static final String RUN_ID = "0123456789abcdef0123456789abcdef01234567";
    private static final UUID STORAGE_UUID =
        UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private static final List<RedisSnapshotFamily> ARBITRARY_ORDER = List.of(
        RedisSnapshotFamily.ROLES,
        RedisSnapshotFamily.PERSONS,
        RedisSnapshotFamily.PRIVILEGES,
        RedisSnapshotFamily.ORGANIZATIONS,
        RedisSnapshotFamily.POSITION_ROLES,
        RedisSnapshotFamily.PARTY_ROLES
    );

    @Test
    void cannotFinishWithoutReadingEveryRequiredFamilyAndFailurePoisonsSession() {
        Fixture fixture = fixture(personEntries(2));
        RedisSnapshotReadSession session = fixture.session(mockVerifier(), fixture.pager(1));

        assertThatThrownBy(session::finish)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("all six");
        assertThatThrownBy(() -> session.readNextPage(RedisSnapshotFamily.PERSONS))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("not open");
    }

    @Test
    void partialReadCannotFinishAndCallerCannotSkipInternalOffsets() {
        Fixture fixture = fixture(personEntries(2));
        List<Long> observedOffsets = new ArrayList<>();
        RedisSnapshotReadSession.PageOperation pager = (handle, family, offset) -> {
            observedOffsets.add(offset);
            return fixture.page(family, offset, 1);
        };
        RedisSnapshotReadSession session = fixture.session(mockVerifier(), pager);

        RedisSnapshotPage first = session.readNextPage(RedisSnapshotFamily.PERSONS);
        RedisSnapshotPage second = session.readNextPage(RedisSnapshotFamily.PERSONS);

        assertThat(first.done()).isFalse();
        assertThat(second.done()).isTrue();
        assertThat(observedOffsets).containsExactly(0L, 1L);
        assertThatThrownBy(session::finish)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("all six");
    }

    @Test
    void completedFamilyCannotBeRepeatedAndRepeatPoisonsWholeSession() {
        Fixture fixture = fixture(personEntries(0));
        RedisSnapshotReadSession session = fixture.session(mockVerifier(), fixture.pager(1));

        assertThat(session.readNextPage(RedisSnapshotFamily.ROLES).done()).isTrue();
        assertThatThrownBy(() -> session.readNextPage(RedisSnapshotFamily.ROLES))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("already complete");
        assertThatThrownBy(() -> session.readNextPage(RedisSnapshotFamily.PERSONS))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("not open");
    }

    @Test
    void crossThreadAccessFailsAndPoisonsTheOwnerSession() throws Exception {
        Fixture fixture = fixture(personEntries(0));
        RedisSnapshotReadSession session = fixture.session(mockVerifier(), fixture.pager(1));
        AtomicReference<Throwable> crossThreadFailure = new AtomicReference<>();
        Thread other = new Thread(() -> {
            try {
                session.readNextPage(RedisSnapshotFamily.ROLES);
            } catch (Throwable failure) {
                crossThreadFailure.set(failure);
            }
        });

        other.start();
        other.join();

        assertThat(crossThreadFailure.get())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("owner thread");
        assertThatThrownBy(() -> session.readNextPage(RedisSnapshotFamily.ROLES))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("not open");
    }

    @Test
    void allSixFamiliesCompleteInArbitraryOrderAndMultipageContentCanFinish() {
        Fixture fixture = fixture(personEntries(2));
        RedisSnapshotReadSession session = fixture.session(mockVerifier(), fixture.pager(1));

        for (RedisSnapshotFamily family : ARBITRARY_ORDER) {
            RedisSnapshotPage page;
            do {
                page = session.readNextPage(family);
            } while (!page.done());
        }
        RedisVerifiedSnapshotView view = session.finish();

        assertThat(view.snapshotId()).isEqualTo(fixture.handle.requestedSnapshotId());
        assertThatThrownBy(session::finish)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("not open");
    }

    @Test
    void crossPageNonCanonicalKeyOrderPoisonsSession() {
        Map<RedisSnapshotFamily, List<RedisCanonicalEntry>> published = personEntries(0);
        published.put(
            RedisSnapshotFamily.PERSONS,
            List.of(entry("b", "one"), entry("c", "two"))
        );
        Fixture fixture = fixture(published);
        RedisSnapshotReadSession.PageOperation reordered = (handle, family, offset) -> {
            if (family != RedisSnapshotFamily.PERSONS) {
                return fixture.page(family, offset, 1);
            }
            RedisCanonicalEntry value = offset == 0
                ? entry("b", "one")
                : entry("a", "two");
            return new RedisSnapshotPage(
                family,
                offset,
                offset + 1,
                List.of(value),
                offset == 1
            );
        };
        RedisSnapshotReadSession session = fixture.session(mockVerifier(), reordered);

        session.readNextPage(RedisSnapshotFamily.PERSONS);
        assertThatThrownBy(() -> session.readNextPage(RedisSnapshotFamily.PERSONS))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("strictly increasing");
        assertThatThrownBy(session::finish)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("not open");
    }

    @Test
    void wrongFamilyOrNonCanonicalPayloadIsTypedAndCannotFinish() {
        Map<RedisSnapshotFamily, List<RedisCanonicalEntry>> published = personEntries(1);
        Fixture fixture = fixture(published);
        RedisSnapshotReadSession.PageOperation corruptPayload = (handle, family, offset) -> {
            if (family == RedisSnapshotFamily.PERSONS) {
                return new RedisSnapshotPage(
                    family,
                    0,
                    1,
                    List.of(new RedisCanonicalEntry(bytes("1"), bytes("{}"))),
                    true
                );
            }
            return fixture.page(family, offset, 1);
        };
        RedisSnapshotReadSession invalidPayload = fixture.session(
            new RedisCanonicalSnapshotEntryVerifier(),
            corruptPayload
        );

        assertThatThrownBy(() -> invalidPayload.readNextPage(RedisSnapshotFamily.PERSONS))
            .isInstanceOf(RedisSnapshotReadException.class)
            .hasMessageEndingWith("PAGE_ENTRY_INVALID");
        assertThatThrownBy(invalidPayload::finish)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("not open");

        RedisCanonicalEntry canonicalPerson = published.get(RedisSnapshotFamily.PERSONS).get(0);
        RedisSnapshotReadSession.PageOperation wrongFamily = (handle, family, offset) -> {
            if (family == RedisSnapshotFamily.ROLES) {
                return new RedisSnapshotPage(
                    family,
                    0,
                    1,
                    List.of(canonicalPerson),
                    true
                );
            }
            return fixture.page(family, offset, 1);
        };
        RedisSnapshotReadSession invalidFamily = fixture.session(
            new RedisCanonicalSnapshotEntryVerifier(),
            wrongFamily
        );
        assertThatThrownBy(() -> invalidFamily.readNextPage(RedisSnapshotFamily.ROLES))
            .isInstanceOf(RedisSnapshotReadException.class)
            .hasMessageEndingWith("PAGE_ENTRY_INVALID");
        assertThatThrownBy(invalidFamily::finish)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("not open");
    }

    @Test
    void actualLogicalBytesCannotExceedSnapshotBudgetAndFailurePoisonsSession() {
        Fixture fixture = fixture(personEntries(1));
        RedisSnapshotReadSession session = fixture.session(
            mockVerifier(),
            fixture.pager(1),
            1
        );

        assertThatThrownBy(() -> session.readNextPage(RedisSnapshotFamily.PERSONS))
            .isInstanceOf(RedisSnapshotReadException.class)
            .hasMessageEndingWith("SNAPSHOT_LIMIT_EXCEEDED");
        assertThatThrownBy(session::finish)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("not open");
    }

    private static RedisCanonicalSnapshotEntryVerifier mockVerifier() {
        return mock(RedisCanonicalSnapshotEntryVerifier.class);
    }

    private static Map<RedisSnapshotFamily, List<RedisCanonicalEntry>> personEntries(
        int count
    ) {
        Map<RedisSnapshotFamily, List<RedisCanonicalEntry>> entries = new EnumMap<>(
            RedisSnapshotFamily.class
        );
        List<RedisCanonicalEntry> persons = new ArrayList<>();
        RedisCanonicalSnapshotPayloadCodec codec = new RedisCanonicalSnapshotPayloadCodec();
        for (int index = 1; index <= count; index++) {
            persons.add(new RedisCanonicalEntry(
                bytes(Integer.toString(index)),
                codec.encode(new PersonDef((long) index, "Person " + index))
            ));
        }
        entries.put(RedisSnapshotFamily.PERSONS, List.copyOf(persons));
        entries.put(RedisSnapshotFamily.ORGANIZATIONS, List.of());
        entries.put(RedisSnapshotFamily.PARTY_ROLES, List.of());
        entries.put(RedisSnapshotFamily.POSITION_ROLES, List.of());
        entries.put(RedisSnapshotFamily.ROLES, List.of());
        entries.put(RedisSnapshotFamily.PRIVILEGES, List.of());
        return entries;
    }

    private static Fixture fixture(
        Map<RedisSnapshotFamily, List<RedisCanonicalEntry>> entries
    ) {
        SecurityDatasetIdentity identity = new SecurityDatasetIdentity("tenant-a", 1);
        SecurityDatasetFence fence = new SecurityDatasetFence(identity, 9);
        RedisSnapshotManifest.PendingPublication pending =
            RedisSnapshotManifest.beginPublication(fence);
        UUID snapshotId = pending.snapshotId();
        RedisDatasetKeyspace keyspace = new RedisDatasetKeyspace("tenant-a");
        RedisSnapshotContentDigest content = content(keyspace, snapshotId, entries);
        RedisSnapshotManifest.Verified manifest = pending.seal(content);
        RedisControlEnvelope control = new RedisControlEnvelope(
            identity,
            new RedisIncarnation(RUN_ID, STORAGE_UUID),
            7,
            snapshotId,
            RedisControlState.READY
        );
        RedisSnapshotGeneration generation = RedisSnapshotGeneration.from(
            new RedisPrimarySnapshot(
                new RedisPrimaryObservation(RUN_ID, "master", false, "noeviction"),
                control
            ),
            identity
        );
        Map<String, String> fields = new RedisSnapshotManifestCodec().encode(manifest);
        RedisSnapshotReadHandle handle = new RedisSnapshotReadHandle(
            generation,
            fence,
            snapshotId,
            fields,
            new RedisSnapshotManifestCodec().decode(fields)
        );
        return new Fixture(handle, entries);
    }

    private static RedisSnapshotContentDigest content(
        RedisDatasetKeyspace keyspace,
        UUID snapshotId,
        Map<RedisSnapshotFamily, List<RedisCanonicalEntry>> entries
    ) {
        Map<RedisSnapshotFamily, RedisSnapshotFamilyDigest> digests = new EnumMap<>(
            RedisSnapshotFamily.class
        );
        List<RedisSnapshotFamily> required = List.of(
            RedisSnapshotFamily.PERSONS,
            RedisSnapshotFamily.ORGANIZATIONS,
            RedisSnapshotFamily.PARTY_ROLES,
            RedisSnapshotFamily.POSITION_ROLES,
            RedisSnapshotFamily.ROLES,
            RedisSnapshotFamily.PRIVILEGES
        );
        for (RedisSnapshotFamily family : required) {
            RedisSnapshotFamilyAccumulator accumulator = new RedisSnapshotFamilyAccumulator(
                familyCode(family),
                keyspace.familyKey(snapshotId, family),
                keyspace.familyIndexKey(snapshotId, family)
            );
            entries.get(family).forEach(accumulator::add);
            digests.put(family, accumulator.finish());
        }
        return new RedisSnapshotContentDigest(
            digests.get(RedisSnapshotFamily.PERSONS),
            digests.get(RedisSnapshotFamily.ORGANIZATIONS),
            digests.get(RedisSnapshotFamily.PARTY_ROLES),
            digests.get(RedisSnapshotFamily.POSITION_ROLES),
            digests.get(RedisSnapshotFamily.ROLES),
            digests.get(RedisSnapshotFamily.PRIVILEGES)
        );
    }

    private static RedisSnapshotFamilyCode familyCode(RedisSnapshotFamily family) {
        return switch (family) {
            case PERSONS -> RedisSnapshotFamilyCode.PERSONS;
            case ORGANIZATIONS -> RedisSnapshotFamilyCode.ORGANIZATIONS;
            case PARTY_ROLES -> RedisSnapshotFamilyCode.PARTY_ROLES;
            case POSITION_ROLES -> RedisSnapshotFamilyCode.POSITION_ROLES;
            case ROLES -> RedisSnapshotFamilyCode.ROLES;
            case PRIVILEGES -> RedisSnapshotFamilyCode.PRIVILEGES;
        };
    }

    private static RedisCanonicalEntry entry(String key, String payload) {
        return new RedisCanonicalEntry(bytes(key), bytes(payload));
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private record Fixture(
        RedisSnapshotReadHandle handle,
        Map<RedisSnapshotFamily, List<RedisCanonicalEntry>> entries
    ) {

        RedisSnapshotReadSession session(
            RedisCanonicalSnapshotEntryVerifier verifier,
            RedisSnapshotReadSession.PageOperation pager
        ) {
            return session(verifier, pager, Long.MAX_VALUE);
        }

        RedisSnapshotReadSession session(
            RedisCanonicalSnapshotEntryVerifier verifier,
            RedisSnapshotReadSession.PageOperation pager,
            long maxAccountedBytes
        ) {
            return new RedisSnapshotReadSession(
                handle,
                verifier,
                pager,
                (recheckedHandle, content) -> RedisVerifiedSnapshotView.adopt(
                    recheckedHandle,
                    recheckedHandle.generation(),
                    recheckedHandle.manifestWireFields(),
                    content
                ),
                maxAccountedBytes
            );
        }

        RedisSnapshotReadSession.PageOperation pager(int pageSize) {
            return (ignoredHandle, family, offset) -> page(family, offset, pageSize);
        }

        RedisSnapshotPage page(RedisSnapshotFamily family, long offset, int pageSize) {
            List<RedisCanonicalEntry> familyEntries = entries.get(family);
            int start = Math.toIntExact(offset);
            int end = Math.min(start + pageSize, familyEntries.size());
            List<RedisCanonicalEntry> pageEntries = familyEntries.subList(start, end);
            return new RedisSnapshotPage(
                family,
                offset,
                end,
                pageEntries,
                end == familyEntries.size()
            );
        }
    }
}
