package com.nomendi6.orgsec.storage.redis.protocol;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.StringJoiner;

import static org.assertj.core.api.Assertions.assertThat;

class RedisStandaloneCoordinatorLeaseScriptsTest {

    @Test
    void everyScriptIsBoundedAsciiAndEnablesCommandReplicationFirst() {
        for (byte[] script : scripts()) {
            assertThat(script.length)
                .isPositive()
                .isLessThanOrEqualTo(RedisPrimaryCommandStream.MAX_SCRIPT_BYTES);
            String source = ascii(script);
            assertThat(source).startsWith("redis.replicate_commands()\n");
            assertThat(source.getBytes(StandardCharsets.US_ASCII)).containsExactly(script);
        }
    }

    @Test
    void scriptsUseTheExactReducedControlManifestAndLeaseShapes() {
        assertThat(RedisControlEnvelopeCodec.REQUIRED_FIELD_COUNT).isEqualTo(7);
        assertThat(RedisSnapshotManifestCodec.REQUIRED_FIELD_COUNT).isEqualTo(12);
        assertThat(RedisCoordinatorLeaseCodec.REQUIRED_FIELD_COUNT).isEqualTo(14);

        int leaseArgumentOffset = leaseArgumentOffset();
        assertThat(ascii(RedisStandaloneCoordinatorLeaseScripts.acquire()))
            .contains("if #KEYS ~= 3 or #ARGV ~= " + (leaseArgumentOffset + 3));
        assertThat(ascii(RedisStandaloneCoordinatorLeaseScripts.renew()))
            .contains("if #KEYS ~= 3 or #ARGV ~= " +
                (leaseArgumentOffset + RedisCoordinatorLeaseCodec.REQUIRED_FIELD_COUNT + 1));
        for (byte[] bytes : List.of(
            RedisStandaloneCoordinatorLeaseScripts.release(),
            RedisStandaloneCoordinatorLeaseScripts.verify()
        )) {
            assertThat(ascii(bytes))
                .contains("if #KEYS ~= 3 or #ARGV ~= " +
                    (leaseArgumentOffset + RedisCoordinatorLeaseCodec.REQUIRED_FIELD_COUNT))
                .contains("local controlNames = " + luaFields(
                    RedisControlEnvelopeCodec.orderedFields()
                ))
                .contains("local leaseNames = " + luaFields(
                    RedisCoordinatorLeaseCodec.orderedFields()
                ))
                .contains("if controlValues[" + luaIndex(
                    RedisControlEnvelopeCodec.orderedFields(),
                    RedisControlEnvelopeCodec.FIELD_PRIMARY_RUN_ID
                ) + "] ~= ARGV[1]")
                .contains("values[" + luaIndex(
                    RedisCoordinatorLeaseCodec.orderedFields(),
                    RedisCoordinatorLeaseCodec.FIELD_PRIMARY_RUN_ID
                ) + "] ~= control[" + luaIndex(
                    RedisControlEnvelopeCodec.orderedFields(),
                    RedisControlEnvelopeCodec.FIELD_PRIMARY_RUN_ID
                ) + "]")
                .contains("state = values[" + luaIndex(
                    RedisCoordinatorLeaseCodec.orderedFields(),
                    RedisCoordinatorLeaseCodec.FIELD_STATE
                ) + "]");
        }
    }

    @Test
    void callersCannotMutateCachedScriptBytes() {
        byte[] first = RedisStandaloneCoordinatorLeaseScripts.acquire();
        byte initial = first[0];
        first[0] = (byte) (initial + 1);

        assertThat(RedisStandaloneCoordinatorLeaseScripts.acquire()[0]).isEqualTo(initial);
    }

    @Test
    void fullTopologyControlLeaseAndCounterPreflightPrecedesEveryWrite() {
        for (byte[] bytes : scripts()) {
            String script = ascii(bytes);
            int firstWrite = firstWriteIndex(script);
            for (String required : List.of(
                "redis.call('INFO', 'server')",
                "redis.call('INFO', 'replication')",
                "redis.call('INFO', 'cluster')",
                "redis.call('INFO', 'memory')",
                "redis.call('TYPE', key)",
                "redis.call('PTTL', key)",
                "redis.call('HLEN', key)",
                "redis.call('HSTRLEN', key, name)",
                "redis.call('HMGET', key, unpack(names))",
                "redis.call('STRLEN', key)",
                "redis.call('GET', key)",
                "redis.call('TIME')"
            )) {
                assertThat(script.indexOf(required))
                    .as(required)
                    .isGreaterThanOrEqualTo(0);
                if (firstWrite >= 0) {
                    assertThat(script.indexOf(required)).as(required).isLessThan(firstWrite);
                }
            }
            assertThat(script)
                .contains("redis.call('PTTL', key) ~= -1")
                .contains("redis.call('HLEN', key) ~= #names")
                .contains("#server > MAX_INFO", "#replication > MAX_INFO")
                .contains("#cluster > MAX_INFO", "#memory > MAX_INFO")
                .contains("infoValue(server, 'run_id') == expectedRunId")
                .contains("infoValue(replication, 'role') == 'master'")
                .contains("infoValue(replication, 'connected_slaves') == '0'")
                .contains("infoValue(cluster, 'cluster_enabled') == '0'")
                .contains("infoValue(memory, 'maxmemory_policy') == 'noeviction'")
                .contains("if controlValues[index] ~= ARGV[2 + index]")
                .contains("if counter == nil or counter < lease.sequence")
                .doesNotContain("redis.call('EXPIRE'", "redis.call('PEXPIRE'")
                .doesNotContain("redis.call('DEL'", "redis.call('UNLINK'")
                .doesNotContain("redis.pcall(");
        }
    }

    @Test
    void acquireAllocatesCounterOnlyAfterAllGuardsAndNeverReusesIt() {
        String script = ascii(RedisStandaloneCoordinatorLeaseScripts.acquire());
        int increment = script.indexOf("redis.call('INCR', KEYS[3])");
        int leaseWrite = script.indexOf("redis.call('HSET', KEYS[2]");

        assertThat(script)
            .contains("if lease.state == 'ACTIVE' and now < lease.expires")
            .contains("if counter == MAX_SAFE then return {'COUNTER_EXHAUSTED'} end")
            .contains("local newSequence = counter + 1")
            .contains("return {'ACQUIRED', nowText, newRevisionText, " +
                "newSequenceText, newExpiryText}")
            .doesNotContain("redis.call('SET', KEYS[3]")
            .doesNotContain("redis.call('HSET', KEYS[1]");
        assertThat(increment).isPositive();
        assertThat(leaseWrite).isGreaterThan(increment);
    }

    @Test
    void renewAndReleaseRequireExactPriorLeaseAndPreserveFenceIdentity() {
        String renew = ascii(RedisStandaloneCoordinatorLeaseScripts.renew());
        String release = ascii(RedisStandaloneCoordinatorLeaseScripts.release());

        for (String script : List.of(renew, release)) {
            assertThat(script.indexOf("for index = 1, #leaseNames do"))
                .isLessThan(script.indexOf("redis.call('HSET', KEYS[2]"));
            assertThat(script)
                .contains("if lease.values[index] ~= ARGV[" +
                    leaseArgumentOffset() + " + index]")
                .contains("if lease.state ~= 'ACTIVE' then return {'LEASE_LOST'} end")
                .contains("if counter ~= lease.sequence then return {'LEASE_CORRUPT'} end")
                .doesNotContain("'fencingSequence', newSequenceText")
                .doesNotContain("redis.call('INCR'");
        }
        assertThat(renew)
            .contains("if now >= lease.expires then return {'LEASE_LOST'} end")
            .contains("if newExpiry < lease.expires then return {'CLOCK_REGRESSED'} end")
            .doesNotContain("'ownerSessionId', ''", "'acquisitionId', ''");
        assertThat(release)
            .contains("'state', 'FREE'")
            .contains("'ownerSessionId', ''", "'acquisitionId', ''")
            .contains("'issuedAtRedisMillis', '0'", "'expiresAtRedisMillis', '0'");
    }

    @Test
    void verifyIsReadOnlyAndUsesHalfOpenExpiryBoundary() {
        String script = ascii(RedisStandaloneCoordinatorLeaseScripts.verify());

        assertThat(script)
            .contains("if now >= lease.expires then return {'LEASE_LOST'} end")
            .contains("return {'MATCH', decimal(now)}")
            .doesNotContain("redis.call('HSET'", "redis.call('INCR'");
    }

    private static List<byte[]> scripts() {
        return List.of(
            RedisStandaloneCoordinatorLeaseScripts.acquire(),
            RedisStandaloneCoordinatorLeaseScripts.renew(),
            RedisStandaloneCoordinatorLeaseScripts.release(),
            RedisStandaloneCoordinatorLeaseScripts.verify()
        );
    }

    private static int leaseArgumentOffset() {
        return 2 + RedisControlEnvelopeCodec.REQUIRED_FIELD_COUNT;
    }

    private static int luaIndex(List<String> fields, String field) {
        return fields.indexOf(field) + 1;
    }

    private static String luaFields(List<String> fields) {
        StringJoiner values = new StringJoiner(", ", "{", "}");
        for (String field : fields) {
            values.add("'" + field + "'");
        }
        return values.toString();
    }

    private static int firstWriteIndex(String script) {
        int hset = script.indexOf("redis.call('HSET'");
        int increment = script.indexOf("redis.call('INCR'");
        if (hset < 0) {
            return increment;
        }
        if (increment < 0) {
            return hset;
        }
        return Math.min(hset, increment);
    }

    private static String ascii(byte[] value) {
        return new String(value, StandardCharsets.US_ASCII);
    }
}
