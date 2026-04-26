package com.nomendi6.orgsec.storage.redis.invalidation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nomendi6.orgsec.storage.redis.cache.L1Cache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.Message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InvalidationEventListenerTest {

    private L1Cache<Long, Object> personCache;
    private L1Cache<Long, Object> organizationCache;
    private L1Cache<Long, Object> roleCache;
    private InvalidationEventListener listener;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        personCache = mock(L1Cache.class);
        organizationCache = mock(L1Cache.class);
        roleCache = mock(L1Cache.class);
        objectMapper = new ObjectMapper();
        listener = new InvalidationEventListener(personCache, organizationCache, roleCache, "local", objectMapper);
    }

    @Test
    void shouldIgnoreOwnEvents() throws Exception {
        listener.onMessage(message(new InvalidationEvent(InvalidationType.PERSON_CHANGED, 1L, "local", 1L)), null);

        verify(personCache, never()).evict(1L);
    }

    @Test
    void shouldEvictPersonOrganizationAndRole() throws Exception {
        listener.onMessage(message(new InvalidationEvent(InvalidationType.PERSON_CHANGED, 1L, "remote", 1L)), null);
        listener.onMessage(message(new InvalidationEvent(InvalidationType.ORG_CHANGED, 2L, "remote", 1L)), null);
        listener.onMessage(message(new InvalidationEvent(InvalidationType.ROLE_CHANGED, 3L, "remote", 1L)), null);

        verify(personCache).evict(1L);
        verify(organizationCache).evict(2L);
        verify(roleCache).evict(3L);
    }

    @Test
    void shouldIgnoreNullEntityIds() throws Exception {
        listener.onMessage(message(new InvalidationEvent(InvalidationType.PERSON_CHANGED, null, "remote", 1L)), null);
        listener.onMessage(message(new InvalidationEvent(InvalidationType.ORG_CHANGED, null, "remote", 1L)), null);
        listener.onMessage(message(new InvalidationEvent(InvalidationType.ROLE_CHANGED, null, "remote", 1L)), null);

        verify(personCache, never()).evict(org.mockito.ArgumentMatchers.any());
        verify(organizationCache, never()).evict(org.mockito.ArgumentMatchers.any());
        verify(roleCache, never()).evict(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void shouldHandlePrivilegeAndSecurityRefreshEvents() throws Exception {
        listener.onMessage(message(new InvalidationEvent(InvalidationType.PRIVILEGE_CHANGED, 4L, "remote", 1L)), null);
        listener.onMessage(message(new InvalidationEvent(InvalidationType.SECURITY_REFRESH, null, "remote", 1L)), null);

        verify(personCache).clear();
        verify(organizationCache).clear();
        verify(roleCache).clear();
    }

    @Test
    void shouldNotPropagateMalformedJson() {
        Message message = mock(Message.class);
        when(message.getBody()).thenReturn("{bad-json".getBytes());

        listener.onMessage(message, null);

        verify(personCache, never()).clear();
    }

    @Test
    @SuppressWarnings("deprecation")
    void deprecatedConstructorShouldUseDefaultObjectMapper() throws Exception {
        InvalidationEventListener deprecatedListener = new InvalidationEventListener(
                personCache, organizationCache, roleCache, "local");

        deprecatedListener.onMessage(message(new InvalidationEvent(InvalidationType.PERSON_CHANGED, 5L, "remote", 1L)), null);

        assertThat(deprecatedListener.getInstanceId()).isEqualTo("local");
        verify(personCache).evict(5L);
    }

    private Message message(InvalidationEvent event) throws Exception {
        Message message = mock(Message.class);
        when(message.getBody()).thenReturn(objectMapper.writeValueAsBytes(event));
        return message;
    }
}
