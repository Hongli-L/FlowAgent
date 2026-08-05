package com.flowagent.common.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Central Redis client configuration.
 *
 * <p>The redisson-spring-boot-starter auto-configuration is excluded in application.yml to avoid
 * a duplicate RedissonClient bean; this factory builds the single client from spring.data.redis
 * so the address stays environment-driven (REDIS_HOST / REDIS_PORT / REDIS_DB).</p>
 */
@Configuration
public class RedisConfiguration {

    @Bean
    @Profile("!local")
    public RedissonClient redissonClient(
            @Value("${spring.data.redis.host:localhost}") String host,
            @Value("${spring.data.redis.port:6379}") int port,
            @Value("${spring.data.redis.database:0}") int database) {
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://" + host + ":" + port)
                .setDatabase(database)
                .setConnectionPoolSize(16)
                .setConnectionMinimumIdleSize(4);
        return Redisson.create(config);
    }
}
