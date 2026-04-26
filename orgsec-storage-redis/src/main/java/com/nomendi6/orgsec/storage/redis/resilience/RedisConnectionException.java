package com.nomendi6.orgsec.storage.redis.resilience;

/**
 * Exception thrown when Redis connection fails.
 * <p>
 * This exception indicates network issues, Redis server downtime,
 * or connection timeout. When this exception is thrown, the application
 * should fall back to database access.
 * </p>
 */
public class RedisConnectionException extends RedisStorageException {

    private final String host;
    private final int port;

    /**
     * Constructs a new Redis connection exception with the specified detail message and cause.
     *
     * @param message the detail message
     * @param cause   the cause of this exception
     */
    public RedisConnectionException(String message, Throwable cause) {
        super(message, cause);
        this.host = null;
        this.port = 0;
    }

    /**
     * Constructs a new Redis connection exception with host and port information.
     *
     * @param message the detail message
     * @param host    the Redis server host
     * @param port    the Redis server port
     * @param cause   the cause of this exception
     */
    public RedisConnectionException(String message, String host, int port, Throwable cause) {
        super(message, cause);
        this.host = host;
        this.port = port;
    }

    /**
     * Returns the Redis server host.
     *
     * @return the host, or null if not available
     */
    public String getHost() {
        return host;
    }

    /**
     * Returns the Redis server port.
     *
     * @return the port, or 0 if not available
     */
    public int getPort() {
        return port;
    }

    @Override
    public String toString() {
        if (host != null) {
            return String.format("RedisConnectionException: %s [host=%s, port=%d]",
                getMessage(), host, port);
        }
        return super.toString();
    }
}
