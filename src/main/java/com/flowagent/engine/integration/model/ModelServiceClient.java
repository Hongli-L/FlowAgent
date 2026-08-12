package com.flowagent.engine.integration.model;

import com.flowagent.engine.integration.model.bo.LlmCallback;
import com.flowagent.engine.integration.model.bo.LlmReqBo;
import com.flowagent.engine.integration.model.bo.LlmResVo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Model service client.
 * Calls console-hub's model API to execute LLM inference.
 *
 */
@Slf4j
@Service
public class ModelServiceClient {

    @Autowired
    private OpenAiStyleLlmIntegration llmIntergration;

    @Autowired
    private ModelResilienceService resilienceService;


    /**
     * Call LLM for chat completion.
     *
     * @param req model request with parameters and prompt
     * @param callback streaming callback for token-by-token response
     */
    public LlmResVo chatCompletion(LlmReqBo req, LlmCallback callback) {
        // Route through the per-endpoint circuit breaker; the breaker is OPEN-fast-fail is
        // translated inside resilienceService into a recoverable ModelInvocationException so the
        // 2.16 multi-model fallback loop can move on to the next model.
        return resilienceService.invoke(req, callback, () -> llmIntergration.call(req, callback));
    }
}
