package com.flowagent.common.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.redisson.api.RateIntervalUnit;
import org.redisson.api.RateType;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RedissonClient;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

import static org.springframework.http.HttpStatus.TOO_MANY_REQUESTS;

/**
 * Weaves {@link RateLimit}: builds a per-dimension token bucket via Redisson RRateLimiter and
 * rejects with HTTP 429 once the bucket is exhausted.
 */
@Slf4j
@Aspect
@Component
public class RateLimitAspect {

    private final RedissonClient redissonClient;
    private static final ExpressionParser PARSER = new SpelExpressionParser();
    private static final DefaultParameterNameDiscoverer DISCOVERER = new DefaultParameterNameDiscoverer();

    public RateLimitAspect(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    @Around("@annotation(rateLimit)")
    public Object around(ProceedingJoinPoint joinPoint, RateLimit rateLimit) throws Throwable {
        String dimensionKey = resolveKey(rateLimit, joinPoint);
        RRateLimiter limiter = redissonClient.getRateLimiter(rateLimit.prefix() + dimensionKey);
        // trySetRate is idempotent: once the rate is set it is a no-op, so calling it per request is safe.
        limiter.trySetRate(RateType.OVERALL, rateLimit.rate(), rateLimit.rateInterval(),
                RateIntervalUnit.valueOf(rateLimit.timeUnit().name()));
        if (!limiter.tryAcquire()) {
            throw new ResponseStatusException(TOO_MANY_REQUESTS, "Rate limit exceeded for " + dimensionKey);
        }
        return joinPoint.proceed();
    }

    private String resolveKey(RateLimit rateLimit, ProceedingJoinPoint joinPoint) {
        String base;
        switch (rateLimit.key()) {
            case USER:
                base = resolveUser();
                break;
            case IP_USER:
                base = resolveIp() + ":" + resolveUser();
                break;
            case IP:
            default:
                base = resolveIp();
                break;
        }
        if (!rateLimit.spelKey().isBlank()) {
            Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
            EvaluationContext context = new StandardEvaluationContext();
            String[] names = DISCOVERER.getParameterNames(method);
            if (names != null) {
                Object[] args = joinPoint.getArgs();
                for (int i = 0; i < names.length && i < args.length; i++) {
                    context.setVariable(names[i], args[i]);
                }
            }
            Expression expr = PARSER.parseExpression(rateLimit.spelKey());
            Object value = expr.getValue(context);
            base = base + ":" + (value == null ? "" : value.toString());
        }
        return base;
    }

    private String resolveIp() {
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs != null) {
                HttpServletRequest request = attrs.getRequest();
                String forwarded = request.getHeader("X-Forwarded-For");
                if (forwarded != null && !forwarded.isBlank()) {
                    return forwarded.split(",")[0].trim();
                }
                return request.getRemoteAddr();
            }
        } catch (Exception ignored) {
            // not in a web request (e.g. unit test) -> fall back to a stable key
        }
        return "local";
    }

    private String resolveUser() {
        // This project has no auth context yet; all callers collapse to a single user bucket.
        return "anonymous";
    }
}
