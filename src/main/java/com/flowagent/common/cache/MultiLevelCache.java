package com.flowagent.common.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Two-level cache: Caffeine (L1, local, fast) backed by Redis (L2, distributed).
 *
 * <p>Read path: L1 hit -> return; L1 miss -> L2 hit -> backfill L1; L2 miss -> loader -> fill both.
 * Write path: put updates L1 and L2 (with random TTL jitter to dampen cache avalanches).</p>
 */
public class MultiLevelCache {

    private final String name;
    private final Cache<String, Object> l1;
    private final RedissonClient redissonClient;
    private final long l2TtlSeconds;

    public MultiLevelCache(String name,
                           RedissonClient redissonClient,
                           long l1TtlSeconds,
                           long l2TtlSeconds,
                           long maxSize) {
        this.name = name;
        this.redissonClient = redissonClient;
        this.l2TtlSeconds = l2TtlSeconds;
        this.l1 = Caffeine.newBuilder()
                .maximumSize(maxSize)
                .expireAfterWrite(l1TtlSeconds, TimeUnit.SECONDS)
                .build();
    }

    private String l2Key(String key) {
        return "cache:" + name + ":" + key;
    }

    @SuppressWarnings("unchecked")
    public <T> T get(String key, Supplier<T> loader) {
        Object cached = l1.getIfPresent(key);
        if (cached != null) {
            return (T) cached;
        }
        RBucket<Object> bucket = redissonClient.getBucket(l2Key(key));
        Object fromL2 = bucket.get();
        if (fromL2 != null) {
            l1.put(key, fromL2);
            return (T) fromL2;
        }
        T loaded = loader.get();
        if (loaded != null) {
            put(key, loaded);
        }
        return loaded;
    }

    public void put(String key, Object value) {
        l1.put(key, value);
        // Random TTL jitter (+/- 10%) so a large set of hot keys does not expire simultaneously.
        long jitter = (long) (l2TtlSeconds * 0.1 * ThreadLocalRandom.current().nextDouble(-1, 1));
        long ttl = Math.max(1, l2TtlSeconds + jitter);
        redissonClient.getBucket(l2Key(key)).set(value, ttl, TimeUnit.SECONDS);
    }

    public void evict(String key) {
        l1.invalidate(key);
        redissonClient.getBucket(l2Key(key)).delete();
    }
}
