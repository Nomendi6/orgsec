package com.nomendi6.orgsec.storage.redis.protocol;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class StrictRedisSnapshotReaderScriptTest {

    @Test
    void manifestScriptBoundsInfoAndBothExactHashesBeforeReturningValues() throws Exception {
        String script = script("READ_MANIFEST_IF_CURRENT");
        String primaryValidation = primaryValidationCall();

        assertThat(script)
            .startsWith("-- READ_MANIFEST_IF_CURRENT")
            .contains(
                "redis.call('INFO', 'server')",
                "redis.call('INFO', 'replication')",
                "redis.call('INFO', 'cluster')",
                "redis.call('INFO', 'memory')",
                "string.len(server) > 65536",
                "readExactHash(KEYS[1]",
                "'CONTROL_CORRUPT', 'GENERATION_CHANGED'",
                primaryValidation,
                "runId ~= expectedRunId or role ~= 'master'",
                "clusterEnabled ~= '0' or maxmemoryPolicy ~= 'noeviction'",
                "readBoundedHash(KEYS[2]",
                "'MANIFEST_CORRUPT'",
                "redis.call('HLEN', key)",
                "redis.call('HSTRLEN', key, name)",
                "redis.call('HMGET', key, unpack(names))",
                "'activeSnapshotId'",
                "'accountedBytes'"
            );
        assertThat(script.indexOf(
            primaryValidation
        )).isLessThan(script.indexOf("readBoundedHash(KEYS[2]"));
        assertNoUnboundedReadCommands(script);
    }

    @Test
    void pageScriptRechecksPhysicalPrimaryControlAndManifestBeforeBoundedDataRead()
        throws Exception {
        String script = script("READ_PAGE_IF_CURRENT");
        String primaryValidation = primaryValidationCall();

        assertThat(script)
            .startsWith("-- READ_PAGE_IF_CURRENT")
            .contains(
                "redis.call('INFO', 'server')",
                "redis.call('INFO', 'replication')",
                "redis.call('INFO', 'cluster')",
                "redis.call('INFO', 'memory')",
                "string.len(server) > 65536",
                "readExactHash(KEYS[1]",
                primaryValidation,
                "readExactHash(KEYS[2]",
                "'MANIFEST_CORRUPT', 'MANIFEST_CHANGED'",
                "redis.call('HLEN', KEYS[3])",
                "redis.call('ZCARD', KEYS[4])",
                "redis.call('ZRANGE', KEYS[4], offset,",
                "'WITHSCORES'",
                "score ~= '0'",
                "redis.call('HSTRLEN', KEYS[3], key)",
                "cumulativeBytes > totalLimit",
                "redis.call('HMGET', KEYS[3], unpack(keys))",
                "control[4], control[2]"
            );
        assertThat(script.indexOf(
            primaryValidation
        )).isLessThan(script.indexOf("readExactHash(KEYS[2]"));
        assertThat(script.indexOf(
            primaryValidation
        )).isLessThan(script.indexOf("redis.call('HLEN', KEYS[3])"));
        assertThat(script.indexOf("redis.call('HSTRLEN', KEYS[3], key)"))
            .isLessThan(script.indexOf("redis.call('HMGET', KEYS[3], unpack(keys))"));
        assertNoUnboundedReadCommands(script);
    }

    private static String script(String fieldName) throws Exception {
        Field field = StrictRedisSnapshotReader.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return new String((byte[]) field.get(null), StandardCharsets.UTF_8);
    }

    private static String primaryValidationCall() {
        int primaryRunIdArgument = RedisControlEnvelopeCodec.orderedFields().indexOf(
            RedisControlEnvelopeCodec.FIELD_PRIMARY_RUN_ID
        ) + 1;
        return "validatePrimary(server, replication, cluster, memory, ARGV[" +
            primaryRunIdArgument + "])";
    }

    private static void assertNoUnboundedReadCommands(String script) {
        assertThat(script)
            .doesNotContain(
                "HGETALL",
                "HKEYS",
                "HVALS",
                "HSCAN",
                "ZRANGE', KEYS[4], 0, -1",
                "redis.call('HGET',"
            );
    }
}
