package com.flowagent.common.cache;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Cache-Aside helpers: read-through on cache miss and delayed double-delete after a write.
 *
 * <p>Delayed double-delete (delete now, delete again after 500ms) protects cache-DB consistency
 * for the short window where a stale value could be repopulated by a concurrent reader.</p>
 */
@Component
public class CacheAsideHelper {

    private final MultiLevelCacheManager cacheManager;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "cache-aside-delayed-delete");
        t.setDaemon(true);
        return t;
    });

    public CacheAsideHelper(MultiLevelCacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    public <T> T readThrough(String cacheName, String key, Supplier<T> loader) {
        return cacheManager.getCache(cacheName).get(key, loader);
    }

    public void evictAfterWrite(String cacheName, String key) {
        cacheManager.evict(cacheName, key);
        scheduler.schedule(() -> cacheManager.evict(cacheName, key), 500, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    public void destroy() {
        scheduler.shutdownNow();
    }
}
