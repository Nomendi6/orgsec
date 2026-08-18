package com.nomendi6.orgsec.storage.redis.protocol;

import io.lettuce.core.ScriptOutputType;

/**
 * One owner-thread command stream backed by one physical Redis connection.
 *
 * <p>The stream is deliberately narrower than either Spring Data Redis or Lettuce. Coordinator
 * code may execute only bounded scripts and the Redis 5+ connection-identity probe.
 * Implementations must never reconnect or move an operation to another native connection. Any
 * transport exception or connection lifecycle event permanently poisons the stream.</p>
 */
interface RedisPrimaryCommandStream extends AutoCloseable {

    int MAX_SCRIPT_BYTES = 64 * 1024;
    int MAX_KEY_COUNT = 64;
    int MAX_KEY_BYTES = 4 * 1024;
    int MAX_ARGUMENT_COUNT = 16 * 1024;
    int MAX_ARGUMENT_BYTES = 4 * 1024 * 1024;
    long MAX_COMMAND_BYTES = 32L * 1024 * 1024;

    /**
     * Executes one script directly on the captured native connection.
     *
     * @param script bounded script bytes
     * @param outputType exact Lettuce response shape
     * @param keys script keys
     * @param arguments script arguments
     * @param <T> decoded response type
     * @return the direct script response
     */
    <T> T eval(
        byte[] script,
        ScriptOutputType outputType,
        byte[][] keys,
        byte[][] arguments
    );

    /**
     * Returns the server-side ID of the captured physical connection after checking continuity.
     *
     * @return positive Redis client ID
     */
    long clientId();

    /** Returns whether a connection event or command failure permanently poisoned this stream. */
    boolean isPoisoned();

    /** Closes the one connection owned by this stream. Repeated owner-thread closes are harmless. */
    @Override
    void close();
}
