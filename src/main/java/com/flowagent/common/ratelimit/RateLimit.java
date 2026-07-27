package com.flowagent.common.ratelimit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.concurrent.TimeUnit;

/**
 * Declarative token-bucket rate limiting backed by a Redisson RRateLimiter.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RateLimit {

    enum Dimension { IP, USER, IP_USER }

    /** Tokens replenished per {@link #rateInterval()} interval. */
    long rate() default 10;

    long rateInterval() default 1;

    Dimension key() default Dimension.IP;

    /** Optional SpEL appended to the dimension key (e.g. "#flowId"). */
    String spelKey() default "";

    String prefix() default "ratelimit:";

    TimeUnit timeUnit() default TimeUnit.SECONDS;
}
