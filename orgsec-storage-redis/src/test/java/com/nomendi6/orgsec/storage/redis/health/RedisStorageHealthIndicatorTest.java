package com.nomendi6.orgsec.storage.redis.health;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisStorageHealthIndicatorTest {

    @Test
    void shouldReturnUpForPong() {
        RedisConnection connection = mock(RedisConnection.class);
        when(connection.ping()).thenReturn("PONG");
        RedisStorageHealthIndicator indicator = indicatorWith(connection);

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("connection", "active");
        assertThat(health.getDetails()).containsEntry("response", "PONG");
        verify(connection).close();
    }

    @Test
    void shouldReturnDownForUnexpectedPingResponse() {
        RedisConnection connection = mock(RedisConnection.class);
        when(connection.ping()).thenReturn("NOPE");
        RedisStorageHealthIndicator indicator = indicatorWith(connection);

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("connection", "failed");
        assertThat(health.getDetails()).containsEntry("response", "NOPE");
        verify(connection).close();
    }

    @Test
    void shouldReturnDownWhenConnectionFails() {
        RedisConnectionFactory connectionFactory = mock(RedisConnectionFactory.class);
        when(connectionFactory.getConnection()).thenThrow(new IllegalStateException("redis unavailable"));
        RedisTemplate<String, String> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        RedisStorageHealthIndicator indicator = new RedisStorageHealthIndicator(template);

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("connection", "failed");
        assertThat(health.getDetails()).containsEntry("error", "IllegalStateException");
    }

    private RedisStorageHealthIndicator indicatorWith(RedisConnection connection) {
        RedisConnectionFactory connectionFactory = mock(RedisConnectionFactory.class);
        when(connectionFactory.getConnection()).thenReturn(connection);
        RedisTemplate<String, String> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        return new RedisStorageHealthIndicator(template);
    }
}
