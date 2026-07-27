package com.flowagent.common.lock;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import redis.embedded.RedisServer;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringJUnitConfig
@TestPropertySource(properties = {
        "spring.data.redis.host=localhost",
        "spring.data.redis.port=16379",
        "spring.data.redis.database=0"
})
class DistributedLockTest {

    private static RedisServer redisServer;

    @BeforeAll
    static void startRedis() throws Exception {
        redisServer = RedisServer.newRedisServer().port(16379).build();
        redisServer.start();
    }

    @AfterAll
    static void stopRedis() throws Exception {
        if (redisServer != null) {
            redisServer.stop();
        }
    }

    @Configuration
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    @Import(com.flowagent.common.config.RedisConfiguration.class)
    static class TestConfig {

        @Bean
        DistributedLockAspect distributedLockAspect(RedissonClient redissonClient) {
            return new DistributedLockAspect(redissonClient);
        }

        @Bean
        LockedService lockedService() {
            return new LockedService();
        }
    }

    static class LockedService {

        // static so the recorder survives CGLIB proxy instantiation (Objenesis skips the constructor)
        static final List<String> order = Collections.synchronizedList(new ArrayList<>());
        static volatile boolean bodyExecuted = false;

        @DistributedLock(key = "#id", leaseTime = 10, waitTime = 5)
        public void critical(String id) throws InterruptedException {
            order.add(Thread.currentThread().getName());
            Thread.sleep(300);
        }

        @DistributedLock(key = "#id", lockType = DistributedLock.LockType.FAIR, leaseTime = 10, waitTime = 5)
        public void fairCritical(String id) {
            bodyExecuted = true;
        }

        @DistributedLock(key = "#id", lockType = DistributedLock.LockType.READ, leaseTime = 10, waitTime = 5)
        public void readCritical(String id) {
            bodyExecuted = true;
        }

        @DistributedLock(key = "#id", lockType = DistributedLock.LockType.WRITE, leaseTime = 10, waitTime = 0)
        public void writeCritical(String id) {
            bodyExecuted = true;
        }

        @DistributedLock(key = "#id", lockType = DistributedLock.LockType.REENTRANT, leaseTime = 10, waitTime = 0,
                failStrategy = DistributedLock.FailStrategy.RETURN_NULL)
        public void returnNullOnFail(String id) {
            bodyExecuted = true;
        }

        @DistributedLock(key = "#id", lockType = DistributedLock.LockType.REENTRANT, leaseTime = 10, waitTime = 0,
                failStrategy = DistributedLock.FailStrategy.CONTINUE)
        public void continueOnFail(String id) {
            bodyExecuted = true;
        }

        @DistributedLock(key = "#id", lockType = DistributedLock.LockType.REENTRANT, leaseTime = 10, waitTime = 0,
                failStrategy = DistributedLock.FailStrategy.EXCEPTION)
        public void exceptionOnFail(String id) {
            bodyExecuted = true;
        }
    }

    @Autowired
    LockedService lockedService;

    @Autowired
    RedissonClient redissonClient;

    @Test
    void serializesConcurrentCallsToSameKey() throws InterruptedException {
        int threads = 3;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            new Thread(() -> {
                try {
                    start.await();
                    lockedService.critical("same-key");
                } catch (Exception ignored) {
                } finally {
                    done.countDown();
                }
            }, "worker-" + i).start();
        }

        long begin = System.nanoTime();
        start.countDown();
        assertTrue(done.await(15, TimeUnit.SECONDS), "all workers finished");
        long elapsedMillis = (System.nanoTime() - begin) / 1_000_000;

        // Sequential execution of 3 x 300ms should take >= ~600ms; parallel would be ~300ms.
        assertEquals(threads, LockedService.order.size());
        assertTrue(elapsedMillis >= 600, "expected serialized execution, took " + elapsedMillis + "ms");
    }

    @Test
    void buildsKeyFromSpelExpression() throws NoSuchMethodException {
        Method method = LockedService.class.getMethod("critical", String.class);
        String key = LockKeyBuilder.build("lock:", "#id", method, new Object[]{"abc-123"});
        assertEquals("lock:abc-123", key);
    }

    @Test
    void buildsKeyFromSpelTemplateExpression() throws NoSuchMethodException {
        Method method = LockedService.class.getMethod("critical", String.class);
        String key = LockKeyBuilder.build("lock:", "user:#{#id}", method, new Object[]{"abc-123"});
        assertEquals("lock:user:abc-123", key);
    }

    @Test
    void fallsBackToMethodNameWhenNoExpression() throws NoSuchMethodException {
        Method method = LockedService.class.getMethod("critical", String.class);
        String key = LockKeyBuilder.build("lock:", "", method, new Object[]{"x"});
        assertEquals("lock:critical", key);
    }

    @Test
    void fairLockAcquiresAndRunsBody() {
        LockedService.bodyExecuted = false;
        lockedService.fairCritical("fair-key");
        assertTrue(LockedService.bodyExecuted, "fair lock body should execute");
    }

    /** Acquires the given lock on a separate thread and releases it when the resource is closed. */
    private AutoCloseable holdLockInOtherThread(org.redisson.api.RLock lock) throws InterruptedException {
        CountDownLatch acquired = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            lock.lock();
            acquired.countDown();
            try {
                release.await(15, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } finally {
                lock.unlock();
            }
        });
        holder.setDaemon(true);
        holder.start();
        if (!acquired.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("holder thread did not acquire the lock in time");
        }
        return () -> {
            release.countDown();
            holder.join(5000);
        };
    }

    @Test
    void readLocksAreSharedAcrossHolders() throws Exception {
        LockedService.bodyExecuted = false;
        var externalRead = redissonClient.getReadWriteLock("lock:rw").readLock();
        try (var ignored = holdLockInOtherThread(externalRead)) {
            // a second reader on another thread must acquire immediately while the first read lock is held
            lockedService.readCritical("rw");
            assertTrue(LockedService.bodyExecuted, "second read lock should be granted concurrently");
        }
    }

    @Test
    void writeLockIsBlockedByReadLock() throws Exception {
        LockedService.bodyExecuted = false;
        var externalRead = redissonClient.getReadWriteLock("lock:rw").readLock();
        try (var ignored = holdLockInOtherThread(externalRead)) {
            // a writer on another thread cannot acquire while a reader holds the lock -> EXCEPTION throws
            assertThrows(DistributedLockException.class, () -> lockedService.writeCritical("rw"));
            assertFalse(LockedService.bodyExecuted, "write body must not run while a read lock is held");
        }
    }

    @Test
    void returnNullStrategySkipsBodyOnContention() throws Exception {
        LockedService.bodyExecuted = false;
        var held = redissonClient.getLock("lock:held");
        try (var ignored = holdLockInOtherThread(held)) {
            // RETURN_NULL on a void method: no exception thrown, body skipped
            lockedService.returnNullOnFail("held");
            assertFalse(LockedService.bodyExecuted, "body must not run when lock not acquired");
        }
    }

    @Test
    void continueStrategyRunsBodyWithoutLock() throws Exception {
        LockedService.bodyExecuted = false;
        var held = redissonClient.getLock("lock:held");
        try (var ignored = holdLockInOtherThread(held)) {
            lockedService.continueOnFail("held");
            assertTrue(LockedService.bodyExecuted, "CONTINUE should run the body despite contention");
        }
    }

    @Test
    void exceptionStrategyThrowsOnContention() throws Exception {
        LockedService.bodyExecuted = false;
        var held = redissonClient.getLock("lock:held");
        try (var ignored = holdLockInOtherThread(held)) {
            assertThrows(DistributedLockException.class, () -> lockedService.exceptionOnFail("held"));
            assertFalse(LockedService.bodyExecuted, "body must not run when lock not acquired");
        }
    }
}
