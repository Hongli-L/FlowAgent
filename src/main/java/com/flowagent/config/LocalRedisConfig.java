package com.flowagent.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Profile;
import redis.embedded.RedisServer;

/**
 * 'local' profile only: starts an embedded Redis on :6379 so the engine runs with zero external
 * services (H2 provides the DB). The app's RedissonClient connects to localhost:6379 as usual.
 *
 * <p>The embedded server is a real Spring bean (initMethod = start) so that the {@code redissonClient}
 * bean can {@code @DependsOn} it and is therefore always created <em>after</em> Redis is listening.
 * This ordering is what keeps the default {@code @PostConstruct} approach from racing the
 * RedissonClient bean, which eagerly connects during creation.</p>
 */
@Configuration
@Profile("local")
public class LocalRedisConfig {

    @Bean(initMethod = "start", destroyMethod = "stop")
    public RedisServer embeddedRedisServer() {
        try {
            return RedisServer.newRedisServer().port(6379).build();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Failed to build embedded Redis server for 'local' profile", e);
        }
    }

    @Bean
    @DependsOn("embeddedRedisServer")
    public RedissonClient redissonClient() {
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://localhost:6379")
                .setDatabase(0)
                .setConnectionPoolSize(16)
                .setConnectionMinimumIdleSize(4);
        return Redisson.create(config);
    }
}
