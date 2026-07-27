package com.flowagent.common.lock;

/**
 * Thrown by {@link DistributedLockAspect} when a distributed lock cannot be acquired or released.
 *
 * <p>Carries the offending {@link #getLockKey()} and a {@link LockErrorType} so callers and
 * monitoring can react differently per failure mode.</p>
 */
public class DistributedLockException extends RuntimeException {

    /** Classifies why the lock operation failed. */
    public enum LockErrorType {
        ACQUIRE_TIMEOUT,
        RELEASE_FAILED,
        REDIS_CONNECTION_ERROR,
        KEY_PARSE_FAILED
    }

    private final String lockKey;
    private final LockErrorType errorType;

    public DistributedLockException(String lockKey, LockErrorType errorType, String message) {
        super(message);
        this.lockKey = lockKey;
        this.errorType = errorType;
    }

    public DistributedLockException(String lockKey, LockErrorType errorType, String message, Throwable cause) {
        super(message, cause);
        this.lockKey = lockKey;
        this.errorType = errorType;
    }

    public String getLockKey() {
        return lockKey;
    }

    public LockErrorType getErrorType() {
        return errorType;
    }
}
