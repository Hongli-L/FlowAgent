package com.flowagent.common.lock;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.redisson.api.RLock;
import org.redisson.api.RReadWriteLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

/**
 * Weaves {@link DistributedLock}: picks the right Redisson lock for {@link DistributedLock.LockType},
 * tries to acquire it, and applies {@link DistributedLock.FailStrategy} on contention.
 *
 * <p>Read locks are shared (many readers at once); write locks and reentrant/fair locks are
 * exclusive. The lock is always released in a {@code finally} block keyed on current-thread
 * ownership to avoid releasing a lock held by another thread.</p>
 */
@Slf4j
@Aspect
@Component
public class DistributedLockAspect {

    private final RedissonClient redissonClient;

    public DistributedLockAspect(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    @Around("@annotation(distributedLock)")
    public Object around(ProceedingJoinPoint joinPoint, DistributedLock distributedLock) throws Throwable {
        Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
        String lockKey = LockKeyBuilder.build(distributedLock.prefix(), distributedLock.key(), method, joinPoint.getArgs());
        RLock lock = getLock(lockKey, distributedLock.lockType());

        boolean acquired = false;
        long startNanos = System.nanoTime();
        try {
            acquired = tryLock(lock, distributedLock);
            if (!acquired) {
                return handleFailure(lockKey, distributedLock, joinPoint);
            }
            if (distributedLock.enableLog()) {
                long costMillis = (System.nanoTime() - startNanos) / 1_000_000;
                log.info("Acquired distributed lock: key={}, type={}, cost={}ms", lockKey, distributedLock.lockType(), costMillis);
            }
            return joinPoint.proceed();
        } finally {
            releaseSafely(lockKey, lock, acquired);
        }
    }

    private RLock getLock(String lockKey, DistributedLock.LockType lockType) {
        return switch (lockType) {
            case REENTRANT -> redissonClient.getLock(lockKey);
            case FAIR -> redissonClient.getFairLock(lockKey);
            case READ -> redissonClient.getReadWriteLock(lockKey).readLock();
            case WRITE -> redissonClient.getReadWriteLock(lockKey).writeLock();
        };
    }

    private boolean tryLock(RLock lock, DistributedLock distributedLock) throws InterruptedException {
        long wait = distributedLock.waitTime();
        long lease = distributedLock.leaseTime();
        TimeUnit unit = distributedLock.timeUnit();
        if (wait <= 0) {
            return lease > 0 ? lock.tryLock(0, lease, unit) : lock.tryLock();
        }
        return lease > 0 ? lock.tryLock(wait, lease, unit) : lock.tryLock(wait, unit);
    }

    private Object handleFailure(String lockKey, DistributedLock distributedLock, ProceedingJoinPoint joinPoint) throws Throwable {
        log.warn("Failed to acquire distributed lock: key={}, waitTime={}{}",
                lockKey, distributedLock.waitTime(), distributedLock.timeUnit().name().toLowerCase());
        return switch (distributedLock.failStrategy()) {
            case EXCEPTION -> throw new DistributedLockException(lockKey,
                    DistributedLockException.LockErrorType.ACQUIRE_TIMEOUT,
                    "Distributed lock acquisition timeout: " + lockKey);
            case RETURN_NULL -> null;
            case CONTINUE -> {
                log.warn("Lock not acquired, continuing without lock: key={}", lockKey);
                yield joinPoint.proceed();
            }
        };
    }

    private void releaseSafely(String lockKey, RLock lock, boolean acquired) {
        if (acquired && lock.isHeldByCurrentThread()) {
            try {
                lock.unlock();
            } catch (IllegalMonitorStateException e) {
                log.error("Failed to release distributed lock: key={}", lockKey, e);
                throw new DistributedLockException(lockKey,
                        DistributedLockException.LockErrorType.RELEASE_FAILED,
                        "Lock release failed: " + lockKey, e);
            }
        }
    }
}
