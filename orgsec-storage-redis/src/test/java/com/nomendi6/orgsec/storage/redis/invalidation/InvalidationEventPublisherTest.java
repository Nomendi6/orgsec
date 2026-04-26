package com.nomendi6.orgsec.storage.redis.invalidation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

class InvalidationEventPublisherTest {

    @Test
    void shouldPublishSyncEvent() {
        RedisTemplate<String, String> redisTemplate = mock(RedisTemplate.class);
        InvalidationEventPublisher publisher = new InvalidationEventPublisher(
                redisTemplate, "channel", false, "instance-1", new ObjectMapper());

        publisher.publishPersonChanged(42L);

        verify(redisTemplate).convertAndSend(eq("channel"), anyString());
        assertThat(publisher.getChannel()).isEqualTo("channel");
        assertThat(publisher.getInstanceId()).isEqualTo("instance-1");
        assertThat(publisher.isAsync()).isFalse();
    }

    @Test
    void shouldPublishAsyncEvent() {
        RedisTemplate<String, String> redisTemplate = mock(RedisTemplate.class);
        InvalidationEventPublisher publisher = new InvalidationEventPublisher(
                redisTemplate, "channel", true, "instance-1", new ObjectMapper());

        publisher.publishOrganizationChanged(42L);

        verify(redisTemplate, timeout(1000)).convertAndSend(eq("channel"), anyString());
        assertThat(publisher.isAsync()).isTrue();
    }

    @Test
    void shouldNotPropagatePublishFailure() {
        RedisTemplate<String, String> redisTemplate = mock(RedisTemplate.class);
        doThrow(new IllegalStateException("redis down")).when(redisTemplate).convertAndSend(eq("channel"), anyString());
        InvalidationEventPublisher publisher = new InvalidationEventPublisher(
                redisTemplate, "channel", false, "instance-1", new ObjectMapper());

        publisher.publishRoleChanged(42L);

        verify(redisTemplate).convertAndSend(eq("channel"), anyString());
    }

    @Test
    void shouldNotPropagateSerializationFailure() throws Exception {
        RedisTemplate<String, String> redisTemplate = mock(RedisTemplate.class);
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        doThrow(new JsonProcessingException("bad json") {}).when(objectMapper).writeValueAsString(org.mockito.ArgumentMatchers.any());
        InvalidationEventPublisher publisher = new InvalidationEventPublisher(
                redisTemplate, "channel", false, "instance-1", objectMapper);

        publisher.publishPrivilegeChanged(42L);

        org.mockito.Mockito.verifyNoInteractions(redisTemplate);
    }

    @Test
    @SuppressWarnings("deprecation")
    void deprecatedConstructorShouldCreateDefaultObjectMapperAndNullInstanceIdShouldGenerateOne() {
        RedisTemplate<String, String> redisTemplate = mock(RedisTemplate.class);

        InvalidationEventPublisher publisher = new InvalidationEventPublisher(redisTemplate, "channel", false, null);

        assertThat(publisher.getChannel()).isEqualTo("channel");
        assertThat(publisher.getInstanceId()).isNotBlank();
        assertThat(publisher.isAsync()).isFalse();
    }
}
