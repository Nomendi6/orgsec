package com.nomendi6.orgsec.storage.redis.protocol;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisClusterConfiguration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisSentinelConfiguration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.RedisStaticMasterReplicaConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class LettuceNonReconnectingPrimaryStreamFactoryTest {

    @Test
    void acceptsOnlyStandaloneLettuceConfiguration() {
        LettuceConnectionFactory source = new LettuceConnectionFactory(
            new RedisStandaloneConfiguration("127.0.0.1", 6379)
        );

        assertThatCode(() -> {
            try (LettuceNonReconnectingPrimaryStreamFactory ignored =
                     new LettuceNonReconnectingPrimaryStreamFactory(source)) {
                // Construction does not open a Redis socket.
            }
        }).doesNotThrowAnyException();
    }

    @Test
    void rejectsCustomFactoryBecauseNativeOwnershipCannotBeProven() {
        RedisConnectionFactory custom = mock(RedisConnectionFactory.class);

        assertThatThrownBy(() -> new LettuceNonReconnectingPrimaryStreamFactory(custom))
            .isInstanceOf(RedisProtocolConfigurationException.class)
            .hasMessageContaining("LettuceConnectionFactory")
            .hasMessageContaining("custom RedisConnectionFactory");
    }

    @Test
    void rejectsSentinelBecauseOnlyDirectPrimaryIsSupported() {
        RedisSentinelConfiguration sentinel = new RedisSentinelConfiguration()
            .master("orgsec-primary")
            .sentinel("127.0.0.1", 26379);
        LettuceConnectionFactory source = new LettuceConnectionFactory(sentinel);

        assertThatThrownBy(() -> new LettuceNonReconnectingPrimaryStreamFactory(source))
            .isInstanceOf(RedisProtocolTopologyException.class)
            .hasMessageContaining("Sentinel")
            .hasMessageContaining("direct standalone Redis primary");
    }

    @Test
    void rejectsRedisCluster() {
        RedisClusterConfiguration cluster = new RedisClusterConfiguration(
            List.of("127.0.0.1:6379")
        );
        LettuceConnectionFactory source = new LettuceConnectionFactory(cluster);

        assertThatThrownBy(() -> new LettuceNonReconnectingPrimaryStreamFactory(source))
            .isInstanceOf(RedisProtocolTopologyException.class)
            .hasMessageContaining("Cluster")
            .hasMessageContaining("unsupported");
    }

    @Test
    void reducesStaticMasterReplicaSourceToItsDirectMasterEndpoint() {
        RedisStaticMasterReplicaConfiguration topology =
            new RedisStaticMasterReplicaConfiguration("127.0.0.1", 6379);
        topology.addNode("127.0.0.1", 6380);
        LettuceConnectionFactory source = new LettuceConnectionFactory(topology);

        assertThatCode(() -> {
            try (LettuceNonReconnectingPrimaryStreamFactory ignored =
                     new LettuceNonReconnectingPrimaryStreamFactory(source)) {
                // Replica nodes and ReadFrom routing are intentionally not copied.
            }
        }).doesNotThrowAnyException();
    }

}
