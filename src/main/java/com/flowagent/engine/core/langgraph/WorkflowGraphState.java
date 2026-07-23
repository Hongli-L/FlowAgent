package com.flowagent.engine.core.langgraph;

import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.state.Channel;
import org.bsc.langgraph4j.state.Channels;

import java.util.HashMap;
import java.util.Map;

/**
 * Shared state carried through LangGraph4j node execution.
 */
public class WorkflowGraphState extends AgentState {

    public static final String LAST_NODE_ID = "lastNodeId";
    public static final String LAST_STATUS = "lastStatus";
    public static final String OUTPUTS = "outputs";

    public static final Map<String, Channel<?>> SCHEMA = Map.of(
            LAST_NODE_ID, Channels.base((java.util.function.Supplier<String>) () -> ""),
            LAST_STATUS, Channels.base((java.util.function.Supplier<String>) () -> ""),
            OUTPUTS, Channels.base((java.util.function.Supplier<Map<String, Object>>) HashMap::new)
    );

    public WorkflowGraphState(Map<String, Object> initData) {
        super(initData);
    }

    public String lastNodeId() {
        return this.<String>value(LAST_NODE_ID).orElse("");
    }

    public String lastStatus() {
        return this.<String>value(LAST_STATUS).orElse("");
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> outputs() {
        return this.<Map<String, Object>>value(OUTPUTS).orElse(Map.of());
    }
}
