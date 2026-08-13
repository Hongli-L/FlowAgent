package com.flowagent.engine.integration.model;

import com.flowagent.common.exception.ModelInvocationException;
import com.flowagent.engine.integration.model.bo.LlmCallback;
import com.flowagent.engine.integration.model.bo.LlmReqBo;
import com.flowagent.engine.integration.model.bo.LlmResVo;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.model.ChatResponse;

import java.time.Duration;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ModelResilienceService}. Uses a locally-built
 * {@link CircuitBreakerRegistry} with a tiny window so behaviour is deterministic and
 * network-free (no real model endpoint is contacted).
 */
class ModelResilienceServiceTest {

    private static final LlmCallback NOOP = (ChatResponse r) -> {
    };

    private final CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(
            CircuitBreakerConfig.custom()
                    .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                    .slidingWindowSize(4)
                    .minimumNumberOfCalls(4)
                    .failureRateThreshold(50)
                    .waitDurationInOpenState(Duration.ofMillis(200))
                    .permittedNumberOfCallsInHalfOpenState(1)
                    .automaticTransitionFromOpenToHalfOpenEnabled(true)
                    .build());

    private final ModelResilienceService service = new ModelResilienceService(registry);

    private static LlmReqBo req(String url, String model) {
        LlmReqBo r = new LlmReqBo();
        r.setUrl(url);
        r.setModel(model);
        return r;
    }

    private static LlmResVo ok() {
        return new LlmResVo(new EmptyUsage(), "ok", "");
    }

    private static Supplier<LlmResVo> boom() {
        return () -> {
            throw new ModelInvocationException("boom", null, 500, true);
        };
    }

    @Test
    void successKeepsBreakerClosed() {
        LlmReqBo r = req("https://api.example.com/v1", "gpt-x");
        LlmResVo out = service.invoke(r, NOOP, ModelResilienceServiceTest::ok);

        assertEquals("ok", out.content());
        assertEquals(CircuitBreaker.State.CLOSED,
                registry.circuitBreaker(ModelResilienceService.breakerName(r)).getState());
    }

    @Test
    void repeatedFailuresOpenBreakerAndThenFastFailIsRecoverable() {
        LlmReqBo r = req("https://api.fail.com/v1", "bad-model");

        // First 4 calls fail (≥50% of the 4-call window) -> breaker opens on the 4th.
        for (int i = 0; i < 4; i++) {
            ModelInvocationException ex = assertThrows(ModelInvocationException.class,
                    () -> service.invoke(r, NOOP, boom()));
            // These are the *original* failures, not the OPEN fast-fail.
            assertEquals("boom", ex.getMessage());
        }

        // Breaker should now be OPEN.
        CircuitBreaker breaker = registry.circuitBreaker(ModelResilienceService.breakerName(r));
        assertEquals(CircuitBreaker.State.OPEN, breaker.getState());

        // The next call is rejected by the OPEN breaker and translated into a *recoverable*
        // ModelInvocationException so the 2.16 fallback loop can move to the next model.
        ModelInvocationException fastFail = assertThrows(ModelInvocationException.class,
                () -> service.invoke(r, NOOP, ModelResilienceServiceTest::ok));
        assertTrue(fastFail.isRecoverable(), "OPEN fast-fail must be recoverable for fallback");
    }

    @Test
    void halfOpenRecoversAfterWait() throws InterruptedException {
        LlmReqBo r = req("https://api.recover.com/v1", "model-r");

        for (int i = 0; i < 4; i++) {
            assertThrows(ModelInvocationException.class,
                    () -> service.invoke(r, NOOP, boom()));
        }
        assertEquals(CircuitBreaker.State.OPEN,
                registry.circuitBreaker(ModelResilienceService.breakerName(r)).getState());

        // Wait past the open-state duration; automatic transition moves it to HALF_OPEN on next call.
        Thread.sleep(250);

        // A successful call in HALF_OPEN closes the breaker.
        LlmResVo out = service.invoke(r, NOOP, ModelResilienceServiceTest::ok);
        assertEquals("ok", out.content());
        assertEquals(CircuitBreaker.State.CLOSED,
                registry.circuitBreaker(ModelResilienceService.breakerName(r)).getState());
    }

    @Test
    void distinctEndpointsHaveIsolatedBreakers() {
        LlmReqBo a = req("https://api.a.com/v1", "model-a");
        LlmReqBo b = req("https://api.b.com/v1", "model-b");

        // Trip only endpoint A.
        for (int i = 0; i < 4; i++) {
            assertThrows(ModelInvocationException.class,
                    () -> service.invoke(a, NOOP, boom()));
        }

        assertEquals(CircuitBreaker.State.OPEN,
                registry.circuitBreaker(ModelResilienceService.breakerName(a)).getState());
        // Endpoint B must be unaffected.
        assertDoesNotThrow(() -> {
            LlmResVo out = service.invoke(b, NOOP, ModelResilienceServiceTest::ok);
            assertEquals("ok", out.content());
        });
        assertEquals(CircuitBreaker.State.CLOSED,
                registry.circuitBreaker(ModelResilienceService.breakerName(b)).getState());
    }
}
