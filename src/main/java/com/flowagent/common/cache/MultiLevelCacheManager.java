package com.flowagent.common.cache;

import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds named {@link MultiLevelCache} instances. L1 (Caffeine) TTL is 30s, L2 (Redis) TTL is 5min.
 */
@Component
public class MultiLevelCacheManager {

    private final RedissonClient redissonClient;
    private final Map<String, MultiLevelCache> caches = new ConcurrentHashMap<>();

    public MultiLevelCacheManager(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    public MultiLevelCache getCache(String name) {
        return caches.computeIfAbsent(name,
                n -> new MultiLevelCache(n, redissonClient, 30, 300, 10_000));
    }

    public void evict(String name, String key) {
        MultiLevelCache cache = caches.get(name);
        if (cache != null) {
            cache.evict(key);
        }
    }
}
