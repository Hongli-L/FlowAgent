package com.flowagent.common.cache;

import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

/**
 * Guard against cache penetration using a Redisson RBloomFilter over workflow ids.
 *
 * <p>A membership check happens before the database lookup; an absent id short-circuits without
 * hitting MySQL. When the filter is not yet initialized we return "present" so the guard never
 * false-rejects a real record.</p>
 */
@Component
public class BloomFilterProxy {

    private final RedissonClient redissonClient;

    public BloomFilterProxy(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    public RBloomFilter<Long> getFilter(String name, long expectedInsertions, double falseProbability) {
        RBloomFilter<Long> filter = redissonClient.getBloomFilter(name);
        if (!filter.isExists()) {
            filter.tryInit(expectedInsertions, falseProbability);
        }
        return filter;
    }

    public boolean mightContain(String name, long id) {
        RBloomFilter<Long> filter = redissonClient.getBloomFilter(name);
        // Safe default: if the filter key does not exist yet, assume present so a real id is never rejected.
        if (!filter.isExists()) {
            return true;
        }
        return filter.contains(id);
    }

    public void put(String name, long id) {
        RBloomFilter<Long> filter = redissonClient.getBloomFilter(name);
        if (!filter.isExists()) {
            filter.tryInit(100_000L, 0.01);
        }
        filter.add(id);
    }
}
