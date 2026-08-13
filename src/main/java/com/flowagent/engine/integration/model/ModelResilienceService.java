package com.flowagent.engine.integration.model;

import com.flowagent.common.exception.ModelInvocationException;
import com.flowagent.engine.integration.model.bo.LlmCallback;
import com.flowagent.engine.integration.model.bo.LlmReqBo;
import com.flowagent.engine.integration.model.bo.LlmResVo;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.circuitbreaker.event.CircuitBreakerOnStateTransitionEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Resilience boundary for external model calls.
 *
 * <p>Wraps every model invocation in a per-endpoint {@link CircuitBreaker} (keyed by the
 * endpoint URL + model name). This pairs with the 2.16 multi-model fallback loop:</p>
 * <ul>
 *   <li><b>Fallback</b> (2.16) decides <i>which</i> model to try next.</li>
 *   <li><b>Circuit breaker</b> (here) decides <i>whether</i> a known-bad model should be
 *       attempted at all — once a model's failure rate trips the breaker, subsequent calls
 *       fail fast instead of hammering an endpoint that is already down.</li>
 * </ul>
 *
 * <p>When the breaker is OPEN a {@link CallNotPermittedException} is raised; we translate it
 * into a recoverable {@link ModelInvocationException} so the 2.16 fallback loop simply moves on
 * to the next configured model. Metrics are bound automatically to Micrometer by the
 * resilience4j-spring-boot3 starter, so breaker state/counts surface on
 * {@code /actuator/metrics} and {@code /actuator/health}.</p>
 */
@Slf4j
@Component
public class ModelResilienceService {

    private final CircuitBreakerRegistry registry;
    private final Set<String> transitionLogged = ConcurrentHashMap.newKeySet();

    public ModelResilienceService(CircuitBreakerRegistry registry) {
        this.registry = registry;
    }

    /**
     * Invoke the model call through a per-endpoint circuit breaker.
     *
     * @param req    the request (used to derive the breaker identity)
     * @param callback streaming callback forwarded to the real call
     * @param call    the actual (blocking) model invocation
     * @return the model response
     * @throws ModelInvocationException when the call fails, or when the breaker is OPEN
     */
    public LlmResVo invoke(LlmReqBo req, LlmCallback callback, Supplier<LlmResVo> call) {
        CircuitBreaker breaker = registry.circuitBreaker(breakerName(req));
        attachStateLogger(breaker);

        Supplier<LlmResVo> decorated = CircuitBreaker.decorateSupplier(breaker, call);
        try {
            return decorated.get();
        } catch (CallNotPermittedException e) {
            // Breaker is OPEN: fail fast and let the 2.16 fallback loop pick the next model.
            throw new ModelInvocationException(
                    "Circuit breaker OPEN for model endpoint " + mask(req.getUrl())
                            + " (model=" + req.getModel() + "); skipping to fallback",
                    e, null, true);
        }
    }

    /** Log state transitions once per breaker instance (avoids duplicate listeners on every call). */
    private void attachStateLogger(CircuitBreaker breaker) {
        if (transitionLogged.add(breaker.getName())) {
            breaker.getEventPublisher().onStateTransition(
                    (CircuitBreakerOnStateTransitionEvent event) -> log.warn(
                            "Circuit breaker [{}] state transition: {} -> {}",
                            breaker.getName(),
                            event.getStateTransition().getFromState(),
                            event.getStateTransition().getToState()));
        }
    }

    /** Stable, registry-safe breaker name derived from the endpoint + model. */
    static String breakerName(LlmReqBo req) {
        String url = req.getUrl() == null ? "unknown" : req.getUrl();
        String model = req.getModel() == null ? "unknown" : req.getModel();
        return "m_" + sanitize(url) + "_" + sanitize(model);
    }

    private static String sanitize(String raw) {
        String s = raw.replaceAll("[^a-zA-Z0-9]", "_");
        return s.length() > 30 ? s.substring(0, 30) : s;
    }

    /** Keep the host, drop the path/key for log friendliness. */
    private static String mask(String url) {
        if (url == null) {
            return "unknown";
        }
        int schemeEnd = url.indexOf("//");
        int pathStart = url.indexOf('/', schemeEnd + 2);
        return pathStart < 0 ? url : url.substring(0, pathStart);
    }
}
