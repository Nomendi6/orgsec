package com.nomendi6.orgsec.storage.redis.protocol;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisChannelHandler;
import io.lettuce.core.RedisConnectionStateListener;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.masterreplica.StatefulRedisMasterReplicaConnection;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnection;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import java.net.SocketAddress;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Creates physically dedicated, non-reconnecting Lettuce streams for coordinator transitions.
 *
 * <p>The supplied application factory is used only as immutable connection configuration. This
 * factory creates and owns a separate non-pooled {@link LettuceConnectionFactory} with native
 * sharing disabled, automatic reconnect disabled, and disconnected commands rejected. Each
 * opened stream therefore borrows a new native connection and keeps it until that stream closes.
 * Sentinel, Redis Cluster, and custom factories remain unsupported until their failover
 * invariants have a separate qualification. A source {@code ReadFrom} preference is deliberately
 * not copied: the private factory always opens a plain connection to the configured standalone
 * endpoint, never a Lettuce master/replica routing connection.</p>
 */
final class LettuceNonReconnectingPrimaryStreamFactory implements AutoCloseable {

    private final LettuceConnectionFactory dedicatedFactory;
    private final Set<LettucePrimaryCommandStream> openStreams = Collections.newSetFromMap(
        new IdentityHashMap<>()
    );
    private final AtomicBoolean dedicatedFactoryCloseStarted = new AtomicBoolean();
    private final AtomicBoolean dedicatedFactoryClosed = new AtomicBoolean();
    private boolean closed;

    LettuceNonReconnectingPrimaryStreamFactory(RedisConnectionFactory sourceFactory) {
        this.dedicatedFactory = createDedicatedFactory(requireStandalone(sourceFactory));
    }

    /** Opens one stream whose physical connection is never shared with another stream. */
    synchronized RedisPrimaryCommandStream open() {
        if (closed) {
            throw new IllegalStateException("Redis primary command stream factory is closed");
        }

        RedisConnection springConnection = acquireConnection();
        LettucePrimaryCommandStream stream;
        try {
            stream = LettucePrimaryCommandStream.open(
                springConnection,
                this::forget
            );
        } catch (RuntimeException | Error failure) {
            closeAfterOpenFailure(springConnection, failure);
            throw failure;
        }
        openStreams.add(stream);
        return stream;
    }

    /** Closes all still-owned streams and then the private Lettuce factory. */
    @Override
    public void close() {
        LettucePrimaryCommandStream[] streams;
        synchronized (this) {
            if (closed && openStreams.isEmpty() && dedicatedFactoryClosed.get()) {
                return;
            }
            closed = true;
            streams = openStreams.toArray(LettucePrimaryCommandStream[]::new);
        }

        Throwable failure = null;
        for (LettucePrimaryCommandStream stream : streams) {
            try {
                stream.forceCloseFromFactory();
            } catch (RuntimeException | Error closeFailure) {
                failure = accumulate(failure, closeFailure);
            }
        }
        if (dedicatedFactoryCloseStarted.compareAndSet(false, true)) {
            try {
                dedicatedFactory.destroy();
                dedicatedFactoryClosed.set(true);
            } catch (RuntimeException | Error destroyFailure) {
                dedicatedFactoryCloseStarted.set(false);
                failure = accumulate(failure, destroyFailure);
            }
        }
        if (failure != null) {
            throw unavailable("Failed to close the dedicated Redis stream factory.", failure);
        }
    }

    private RedisConnection acquireConnection() {
        try {
            RedisConnection connection = dedicatedFactory.getConnection();
            if (connection == null) {
                throw unavailable("Dedicated Lettuce factory returned no connection.");
            }
            return connection;
        } catch (RedisProtocolException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw unavailable("Failed to open a dedicated Redis command stream.", failure);
        }
    }

    private synchronized void forget(LettucePrimaryCommandStream stream) {
        openStreams.remove(stream);
    }

    private static LettuceConnectionFactory requireStandalone(
        RedisConnectionFactory sourceFactory
    ) {
        Objects.requireNonNull(sourceFactory, "sourceFactory must not be null");
        if (!(sourceFactory instanceof LettuceConnectionFactory lettuceFactory)) {
            throw new RedisProtocolConfigurationException(
                "Strict Redis publication requires a LettuceConnectionFactory so physical "
                    + "connection ownership can be proven; custom RedisConnectionFactory "
                    + "implementations are unsupported."
            );
        }
        if (lettuceFactory.isClusterAware() || lettuceFactory.getClusterConfiguration() != null) {
            throw new RedisProtocolTopologyException(
                "Redis Cluster is unsupported by the strict publication stream."
            );
        }
        if (lettuceFactory.isRedisSentinelAware()
            || lettuceFactory.getSentinelConfiguration() != null) {
            throw new RedisProtocolTopologyException(
                "Redis Sentinel is unsupported; configure a direct standalone Redis primary."
            );
        }
        if (lettuceFactory.getStandaloneConfiguration() == null) {
            throw new RedisProtocolTopologyException(
                "Only a direct standalone Redis primary is supported by this publication "
                    + "stream; the source factory exposes no standalone primary endpoint."
            );
        }
        return lettuceFactory;
    }

    private static LettuceConnectionFactory createDedicatedFactory(
        LettuceConnectionFactory sourceFactory
    ) {
        RedisStandaloneConfiguration server = copyServerConfiguration(
            sourceFactory.getStandaloneConfiguration()
        );
        LettuceClientConfiguration client = copyClientConfiguration(
            sourceFactory.getClientConfiguration()
        );
        LettuceConnectionFactory result = new LettuceConnectionFactory(server, client);
        result.setShareNativeConnection(false);
        result.setValidateConnection(false);
        try {
            result.afterPropertiesSet();
            return result;
        } catch (RuntimeException | Error failure) {
            try {
                result.destroy();
            } catch (RuntimeException | Error destroyFailure) {
                failure.addSuppressed(destroyFailure);
            }
            throw failure;
        }
    }

    private static RedisStandaloneConfiguration copyServerConfiguration(
        RedisStandaloneConfiguration source
    ) {
        RedisStandaloneConfiguration copy = new RedisStandaloneConfiguration(
            source.getHostName(),
            source.getPort()
        );
        copy.setDatabase(source.getDatabase());
        copy.setUsername(source.getUsername());
        RedisPassword password = source.getPassword();
        if (password != null && password.isPresent()) {
            copy.setPassword(password);
        }
        return copy;
    }

    private static LettuceClientConfiguration copyClientConfiguration(
        LettuceClientConfiguration source
    ) {
        ClientOptions sourceOptions = source.getClientOptions().orElseGet(ClientOptions::create);
        ClientOptions strictOptions = sourceOptions.mutate()
            .autoReconnect(false)
            .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
            .build();

        LettuceClientConfiguration.LettuceClientConfigurationBuilder builder =
            LettuceClientConfiguration.builder()
                .clientOptions(strictOptions)
                .commandTimeout(source.getCommandTimeout())
                .shutdownTimeout(source.getShutdownTimeout())
                .shutdownQuietPeriod(source.getShutdownQuietPeriod());
        source.getClientResources().ifPresent(builder::clientResources);
        source.getClientName().ifPresent(builder::clientName);
        source.getRedisCredentialsProviderFactory().ifPresent(
            builder::redisCredentialsProviderFactory
        );
        if (source.isUseSsl()) {
            LettuceClientConfiguration.LettuceSslClientConfigurationBuilder ssl =
                builder.useSsl().verifyPeer(source.getVerifyMode());
            if (source.isStartTls()) {
                ssl.startTls();
            }
            builder = ssl.and();
        }
        return builder.build();
    }

    private static void closeAfterOpenFailure(
        RedisConnection connection,
        Throwable pendingFailure
    ) {
        try {
            connection.close();
        } catch (RuntimeException closeFailure) {
            pendingFailure.addSuppressed(closeFailure);
        }
    }

    private static Throwable accumulate(Throwable current, Throwable next) {
        if (current == null) {
            return next;
        }
        current.addSuppressed(next);
        return current;
    }

    private static RedisProtocolUnavailableException unavailable(String detail) {
        return new RedisProtocolUnavailableException(detail);
    }

    private static RedisProtocolUnavailableException unavailable(
        String detail,
        Throwable cause
    ) {
        return new RedisProtocolUnavailableException(detail, cause);
    }

    private static final class LettucePrimaryCommandStream
        implements RedisPrimaryCommandStream {

        private final RedisConnection springConnection;
        private final StatefulRedisConnection<byte[], byte[]> nativeConnection;
        private final RedisCommands<byte[], byte[]> commands;
        private final RedisConnectionStateListener connectionListener;
        private final Thread ownerThread;
        private final long initialClientId;
        private final java.util.function.Consumer<LettucePrimaryCommandStream> onClose;
        private final AtomicBoolean poisoned = new AtomicBoolean();
        private final AtomicReference<Throwable> poisonCause = new AtomicReference<>();
        private final AtomicBoolean connectionClosed = new AtomicBoolean();

        private LettucePrimaryCommandStream(
            RedisConnection springConnection,
            StatefulRedisConnection<byte[], byte[]> nativeConnection,
            RedisCommands<byte[], byte[]> commands,
            long initialClientId,
            java.util.function.Consumer<LettucePrimaryCommandStream> onClose
        ) {
            this.springConnection = springConnection;
            this.nativeConnection = nativeConnection;
            this.commands = commands;
            this.initialClientId = initialClientId;
            this.onClose = onClose;
            this.ownerThread = Thread.currentThread();
            this.connectionListener = new RedisConnectionStateListener() {
                @Override
                public void onRedisConnected(
                    RedisChannelHandler<?, ?> connection,
                    SocketAddress socketAddress
                ) {
                    poison(new IllegalStateException(
                        "A closed Redis publication stream attempted to reconnect"
                    ));
                }

                @Override
                public void onRedisDisconnected(RedisChannelHandler<?, ?> connection) {
                    poison(new IllegalStateException(
                        "The Redis publication stream disconnected"
                    ));
                }

                @Override
                public void onRedisExceptionCaught(
                    RedisChannelHandler<?, ?> connection,
                    Throwable cause
                ) {
                    poison(cause == null
                        ? new IllegalStateException(
                            "The Redis publication stream reported a connection exception"
                        )
                        : cause);
                }
            };
        }

        @SuppressWarnings("deprecation") // Lettuce 6.6 exposes the owning stateful connection here.
        static LettucePrimaryCommandStream open(
            RedisConnection springConnection,
            java.util.function.Consumer<LettucePrimaryCommandStream> onClose
        ) {
            if (!(springConnection instanceof LettuceConnection lettuceConnection)) {
                throw new RedisProtocolConfigurationException(
                    "Dedicated publication factory returned a non-Lettuce connection."
                );
            }

            Object rawCommands = lettuceConnection.getNativeConnection();
            if (!(rawCommands instanceof RedisAsyncCommands<?, ?> untypedCommands)) {
                throw new RedisProtocolConfigurationException(
                    "Dedicated publication connection did not expose standalone Lettuce "
                        + "commands."
                );
            }
            @SuppressWarnings("unchecked")
            RedisAsyncCommands<byte[], byte[]> asyncCommands =
                (RedisAsyncCommands<byte[], byte[]>) untypedCommands;
            StatefulRedisConnection<byte[], byte[]> nativeConnection =
                asyncCommands.getStatefulConnection();
            if (nativeConnection instanceof StatefulRedisMasterReplicaConnection<?, ?>) {
                throw new RedisProtocolTopologyException(
                    "Lettuce master/replica routing connections are unsupported by the "
                        + "dedicated Redis publication stream."
                );
            }
            ClientOptions options = nativeConnection.getOptions();
            if (options.isAutoReconnect()
                || options.getDisconnectedBehavior()
                    != ClientOptions.DisconnectedBehavior.REJECT_COMMANDS) {
                throw new RedisProtocolConfigurationException(
                    "Dedicated publication connection must disable automatic reconnect and "
                        + "reject commands while disconnected."
                );
            }
            if (!nativeConnection.isOpen()) {
                throw unavailable("Dedicated Redis native connection is not open.");
            }

            RedisCommands<byte[], byte[]> commands = nativeConnection.sync();
            Long initialClientId;
            try {
                initialClientId = commands.clientId();
            } catch (RuntimeException failure) {
                throw unavailable("Failed to read the dedicated Redis client ID.", failure);
            }
            if (initialClientId == null || initialClientId <= 0) {
                throw unavailable("Redis returned an invalid dedicated client ID.");
            }

            LettucePrimaryCommandStream stream = new LettucePrimaryCommandStream(
                springConnection,
                nativeConnection,
                commands,
                initialClientId,
                onClose
            );
            nativeConnection.addListener(stream.connectionListener);
            return stream;
        }

        @Override
        public <T> T eval(
            byte[] script,
            ScriptOutputType outputType,
            byte[][] keys,
            byte[][] arguments
        ) {
            requireOwnerAndUsable("execute a Redis script");
            requireScriptArguments(script, outputType, keys, arguments);
            try {
                T result = commands.eval(
                    script.clone(),
                    outputType,
                    deepCopy(keys),
                    deepCopy(arguments)
                );
                verifyContinuity();
                return result;
            } catch (RuntimeException failure) {
                poison(failure);
                throw unavailable("Dedicated Redis script execution failed.", failure);
            }
        }

        @Override
        public long clientId() {
            requireOwnerAndUsable("read the Redis client ID");
            try {
                verifyContinuity();
                return initialClientId;
            } catch (RuntimeException failure) {
                poison(failure);
                if (failure instanceof RedisProtocolException protocolFailure) {
                    throw protocolFailure;
                }
                throw unavailable("Dedicated Redis client-ID check failed.", failure);
            }
        }

        @Override
        public boolean isPoisoned() {
            return poisoned.get();
        }

        @Override
        public void close() {
            requireOwnerThread();
            closeConnection();
        }

        void forceCloseFromFactory() {
            closeConnection();
        }

        private void verifyContinuity() {
            if (poisoned.get() || !nativeConnection.isOpen()) {
                throw poisonedFailure();
            }
            Long currentClientId = commands.clientId();
            if (currentClientId == null || currentClientId != initialClientId) {
                throw new RedisProtocolUnavailableException(
                    "Redis publication stream changed its physical client ID."
                );
            }
            if (poisoned.get() || !nativeConnection.isOpen()) {
                throw poisonedFailure();
            }
        }

        private void requireOwnerAndUsable(String operation) {
            requireOwnerThread();
            if (connectionClosed.get()) {
                throw new IllegalStateException(
                    "cannot " + operation + " after the Redis command stream closed"
                );
            }
            if (poisoned.get() || !nativeConnection.isOpen()) {
                throw poisonedFailure();
            }
        }

        private void requireOwnerThread() {
            if (Thread.currentThread() != ownerThread) {
                throw new IllegalStateException(
                    "Redis primary command stream may only be used by its owner thread"
                );
            }
        }

        private RedisProtocolUnavailableException poisonedFailure() {
            Throwable cause = poisonCause.get();
            if (cause == null) {
                return unavailable("Redis primary command stream is permanently poisoned.");
            }
            return unavailable(
                "Redis primary command stream is permanently poisoned.",
                cause
            );
        }

        private void poison(Throwable cause) {
            poisoned.set(true);
            if (cause != null) {
                poisonCause.compareAndSet(null, cause);
            }
        }

        private synchronized void closeConnection() {
            if (!connectionClosed.compareAndSet(false, true)) {
                return;
            }
            nativeConnection.removeListener(connectionListener);
            Throwable failure = null;
            try {
                springConnection.close();
            } catch (RuntimeException | Error closeFailure) {
                poison(closeFailure);
                failure = closeFailure;
            }
            if (nativeConnection.isOpen()) {
                try {
                    nativeConnection.close();
                } catch (RuntimeException | Error nativeCloseFailure) {
                    failure = accumulate(failure, nativeCloseFailure);
                }
            }
            if (!nativeConnection.isOpen()) {
                onClose.accept(this);
            } else {
                connectionClosed.set(false);
                nativeConnection.addListener(connectionListener);
                RedisProtocolUnavailableException stillOpen = unavailable(
                    "Dedicated Redis native connection remained open after close."
                );
                failure = accumulate(failure, stillOpen);
            }
            if (failure != null) {
                throw unavailable("Failed to close the dedicated Redis command stream.", failure);
            }
        }

        private static void requireScriptArguments(
            byte[] script,
            ScriptOutputType outputType,
            byte[][] keys,
            byte[][] arguments
        ) {
            if (script == null || script.length == 0) {
                throw new IllegalArgumentException("script must not be empty");
            }
            if (script.length > MAX_SCRIPT_BYTES) {
                throw new IllegalArgumentException("script exceeds MAX_SCRIPT_BYTES");
            }
            Objects.requireNonNull(outputType, "outputType must not be null");
            long totalBytes = script.length;
            totalBytes = requireByteArrays(
                keys,
                "keys",
                MAX_KEY_COUNT,
                MAX_KEY_BYTES,
                false,
                totalBytes
            );
            requireByteArrays(
                arguments,
                "arguments",
                MAX_ARGUMENT_COUNT,
                MAX_ARGUMENT_BYTES,
                true,
                totalBytes
            );
        }

        private static long requireByteArrays(
            byte[][] values,
            String name,
            int maxCount,
            int maxValueBytes,
            boolean emptyValueAllowed,
            long initialBytes
        ) {
            Objects.requireNonNull(values, name + " must not be null");
            if (values.length > maxCount) {
                throw new IllegalArgumentException(name + " exceeds its count limit");
            }
            long totalBytes = initialBytes;
            for (byte[] value : values) {
                if (value == null) {
                    throw new IllegalArgumentException(name + " must not contain null values");
                }
                if (!emptyValueAllowed && value.length == 0) {
                    throw new IllegalArgumentException(name + " must not contain empty values");
                }
                if (value.length > maxValueBytes) {
                    throw new IllegalArgumentException(name + " contains an oversized value");
                }
                totalBytes += value.length;
                if (totalBytes > MAX_COMMAND_BYTES) {
                    throw new IllegalArgumentException("Redis command exceeds MAX_COMMAND_BYTES");
                }
            }
            return totalBytes;
        }

        private static byte[][] deepCopy(byte[][] values) {
            return Arrays.stream(values).map(byte[]::clone).toArray(byte[][]::new);
        }
    }
}
