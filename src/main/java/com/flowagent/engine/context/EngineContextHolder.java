package com.flowagent.engine.context;

import com.alibaba.ttl.TransmittableThreadLocal;
import com.flowagent.engine.node.callback.WorkflowMsgCallback;
import com.flowagent.engine.util.FlowUtil;
import lombok.Data;

/**
 * Workflow engine execution context holder, propagated via TTL across thread boundaries.
 */
public class EngineContextHolder {
    private static TransmittableThreadLocal<EngineContext> contexts = new TransmittableThreadLocal<>();

    public static void set(EngineContext context) {
        contexts.set(context);
    }

    public static EngineContext get() {
        return contexts.get();
    }

    public static void remove() {
        contexts.remove();
    }


    public static EngineContext initContext(String flowId, String chatId, WorkflowMsgCallback workflowCallback) {
        EngineContext context = new EngineContext();
        context.setFlowId(flowId);
        context.setChatId(chatId);
        context.setCallback(workflowCallback);
        context.setSid(FlowUtil.genSid());
        set(context);
        return context;
    }

    @Data
    public static class EngineContext {
        private String flowId;

        private String chatId;

        private WorkflowMsgCallback callback;

        private String sid;

        /** Snowflake execution id, set by the engine when a run starts (used by node tracing). */
        private Long executionId;
    }
}
