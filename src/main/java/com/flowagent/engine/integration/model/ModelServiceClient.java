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
 * @version 1.0.0
 */
@Slf4j
@Service
public class ModelServiceClient {

    @Autowired
    private OpenAiStyleLlmIntegration llmIntergration;


    /**
     * Call LLM for chat completion.
     *
     * @param req model request with parameters and prompt
     * @param callback streaming callback for token-by-token response
     */
    public LlmResVo chatCompletion(LlmReqBo req, LlmCallback callback) {
        return llmIntergration.call(req, callback);
    }
}
