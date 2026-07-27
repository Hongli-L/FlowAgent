package com.flowagent.common.lock;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.concurrent.TimeUnit;

/**
 * Declarative distributed lock backed by a Redisson RLock.
 *
 * <p>Supports multiple lock kinds via {@link #lockType()} and configurable failure behaviour via
 * {@link #failStrategy()}.</p>
 *
 * <p>Examples:
 * <pre>
 *   // reentrant, default behaviour
 *   &#64;DistributedLock(key = "#flowId", leaseTime = 30, waitTime = 5)
 *
 *   // fair lock with a SpEL template key
 *   &#64;DistributedLock(key = "order:#{#orderId}", lockType = LockType.FAIR)
 *
 *   // write lock, return null instead of throwing on contention
 *   &#64;DistributedLock(key = "#docId", lockType = LockType.WRITE, failStrategy = FailStrategy.RETURN_NULL)
 * </pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface DistributedLock {

    /** SpEL expression (or {@code #{...}} template) resolved against method arguments for the key. */
    String key() default "";

    /** Kind of lock to acquire. Defaults to a reentrant lock. */
    LockType lockType() default LockType.REENTRANT;

    /** Behaviour when the lock cannot be acquired within {@link #waitTime()}. */
    FailStrategy failStrategy() default FailStrategy.EXCEPTION;

    /** Auto-release lease time once acquired (scaled by {@link #timeUnit()}). */
    long leaseTime() default 30;

    /** Max time to block waiting for the lock before applying {@link #failStrategy()}. */
    long waitTime() default 5;

    TimeUnit timeUnit() default TimeUnit.SECONDS;

    /** Key namespace prefix to avoid collisions across business domains. */
    String prefix() default "lock:";

    /** Whether to log acquisition/cost. */
    boolean enableLog() default true;

    /** Lock kinds supported by Redisson. */
    enum LockType {
        /** Reentrant lock (default); the same thread may re-acquire it. */
        REENTRANT,
        /** Fair lock; granted in the order requests were made. */
        FAIR,
        /** Read side of a read-write lock; multiple readers run concurrently. */
        READ,
        /** Write side of a read-write lock; exclusive against readers and writers. */
        WRITE
    }

    /** What to do when the lock cannot be acquired. */
    enum FailStrategy {
        /** Throw {@link DistributedLockException}. */
        EXCEPTION,
        /** Return {@code null} without running the method body. */
        RETURN_NULL,
        /** Run the method body anyway, without holding the lock. */
        CONTINUE
    }
}
