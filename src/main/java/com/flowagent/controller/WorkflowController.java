package com.flowagent.controller;

import com.flowagent.common.ratelimit.RateLimit;
import com.flowagent.engine.WorkflowContextStore;
import com.flowagent.engine.core.EngineFactory;
import com.flowagent.engine.util.AsyncUtil;
import com.flowagent.engine.dsl.model.WorkflowDSL;
import com.flowagent.engine.node.FlowEventCallback;
import com.flowagent.engine.node.callback.SseFlowEventCallback;
import com.flowagent.persistence.service.WorkflowService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/workflow")
@Tag(name = "Workflow Execution", description = "Streaming execution entrypoint for a workflow")
public class WorkflowController {

    private final WorkflowService workflowService;
    private final EngineFactory engineFactory;

    public WorkflowController(WorkflowService workflowService, EngineFactory engineFactory) {
        this.workflowService = workflowService;
        this.engineFactory = engineFactory;
    }

    @RateLimit(rate = 20, rateInterval = 1, key = RateLimit.Dimension.IP)
    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Execute workflow (SSE)", description = "Runs a workflow by flowId and streams node events over Server-Sent Events.")
    public SseEmitter executeWorkflow(@RequestBody WorkflowRequest request) {
        log.info("Workflow execution request: flowId={}, engine={}, mode={}, inputs={}",
                request.getFlowId(), engineFactory.activeType(), engineFactory.activeMode(), request.getInputs());

        SseEmitter emitter = new SseEmitter(600_000L);

        AsyncUtil.execute(() -> {
            try {
                WorkflowDSL workflowDSL = workflowService.getWorkflowDSL(request.getFlowId());
                workflowDSL.setUuid(request.getChatId());

                FlowEventCallback callback = new SseFlowEventCallback(emitter);

                engineFactory.getEngine().execute(workflowDSL, new WorkflowContextStore(), request.getInputs(), callback);

                emitter.complete();

            } catch (Exception e) {
                log.error("Workflow execution failed: {}", e.getMessage(), e);
                emitter.completeWithError(e);
            }
        });

        emitter.onTimeout(() -> {
            log.warn("Workflow execution timeout");
            emitter.complete();
        });

        emitter.onError(e -> {
            log.error("SSE error: {}", e.getMessage(), e);
            emitter.completeWithError(e);
        });

        return emitter;
    }

    @Data
    public static class WorkflowRequest {

        @com.fasterxml.jackson.annotation.JsonProperty("flow_id")
        private String flowId;

        @com.fasterxml.jackson.annotation.JsonProperty("inputs")
        private Map<String, Object> inputs;

        @com.fasterxml.jackson.annotation.JsonProperty("chatId")
        private String chatId;

        @com.fasterxml.jackson.annotation.JsonProperty("regen")
        private Boolean regen;
    }
}
