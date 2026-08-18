package com.nomendi6.orgsec.storage.redis.protocol;

/**
 * Publication state of a Redis security dataset.
 *
 * <p>Only {@link #READY} permits authorization reads. Both transitional states are deliberately
 * fail-closed.</p>
 */
enum RedisControlState {
    /** No complete snapshot has been published yet. */
    INITIALIZING,

    /** A mutation, refresh or compatibility cutover is in progress. */
    UPDATING,

    /** The active snapshot is complete and may serve authorization reads. */
    READY
}
