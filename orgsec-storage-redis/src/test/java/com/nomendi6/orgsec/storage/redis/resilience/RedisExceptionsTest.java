package com.nomendi6.orgsec.storage.redis.resilience;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RedisExceptionsTest {

    @Test
    void shouldExposeRedisConnectionExceptionContext() {
        RuntimeException cause = new RuntimeException("root");
        RedisConnectionException basic = new RedisConnectionException("failed", cause);
        RedisConnectionException withAddress = new RedisConnectionException("failed", "localhost", 6379, cause);

        assertThat(basic.getHost()).isNull();
        assertThat(basic.getPort()).isZero();
        assertThat(basic).hasCause(cause);
        assertThat(basic.toString()).contains("failed");
        assertThat(withAddress.getHost()).isEqualTo("localhost");
        assertThat(withAddress.getPort()).isEqualTo(6379);
        assertThat(withAddress.toString()).contains("host=localhost", "port=6379");
    }

    @Test
    void shouldExposeCacheSerializationExceptionContext() {
        RuntimeException cause = new RuntimeException("json");
        CacheSerializationException basic = new CacheSerializationException("failed", cause);
        String longJson = "x".repeat(120);
        CacheSerializationException detailed = new CacheSerializationException("failed", longJson, String.class, cause);

        assertThat(basic.getJson()).isNull();
        assertThat(basic.getTargetType()).isNull();
        assertThat(basic).hasCause(cause);
        assertThat(basic.toString()).contains("failed");
        assertThat(detailed.getJson()).isEqualTo(longJson);
        assertThat(detailed.getTargetType()).isEqualTo(String.class);
        assertThat(detailed.toString()).contains("targetType=String").contains("...");
    }

    @Test
    void shouldExposeCacheIntegrityExceptionContext() {
        CacheIntegrityException exception = new CacheIntegrityException("bad hash", "key", "expected", "actual");

        assertThat(exception.getKey()).isEqualTo("key");
        assertThat(exception.getExpectedHash()).isEqualTo("expected");
        assertThat(exception.getActualHash()).isEqualTo("actual");
        assertThat(exception.toString()).contains("bad hash", "key=key", "expected=expected", "actual=actual");
    }

    @Test
    void shouldCreateRedisStorageExceptionVariants() {
        RuntimeException cause = new RuntimeException("root");

        RedisStorageException messageOnly = new RedisStorageException("message");
        RedisStorageException withCause = new RedisStorageException("message", cause);

        assertThat(messageOnly).hasMessage("message");
        assertThat(withCause).hasMessage("message").hasCause(cause);
    }
}
