package com.flowagent.common.ratelimit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.web.server.ResponseStatusException;
import redis.embedded.RedisServer;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringJUnitConfig
@TestPropertySource(properties = {
        "spring.data.redis.host=localhost",
        "spring.data.redis.port=16379",
        "spring.data.redis.database=0"
})
class RateLimitTest {

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
        RateLimitAspect rateLimitAspect(RedissonClient redissonClient) {
            return new RateLimitAspect(redissonClient);
        }

        @Bean
        LimitedService limitedService() {
            return new LimitedService();
        }
    }

    static class LimitedService {

        @RateLimit(rate = 5, rateInterval = 1, key = RateLimit.Dimension.IP)
        void hit() {
            // no-op; aspect gates entry
        }
    }

    @org.springframework.beans.factory.annotation.Autowired
    LimitedService limitedService;

    @Test
    void rejectsBeyondTokenBudget() {
        int allowed = 0;
        int rejected = 0;
        for (int i = 0; i < 10; i++) {
            try {
                limitedService.hit();
                allowed++;
            } catch (ResponseStatusException e) {
                if (e.getStatusCode().value() == 429) {
                    rejected++;
                } else {
                    throw e;
                }
            }
        }
        assertEquals(5, allowed, "first 5 calls should pass");
        assertEquals(5, rejected, "next 5 calls should be throttled");
    }
}
