package com.flowagent.engine.agent;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.flowagent.engine.WorkflowContextStore;
import com.flowagent.engine.integration.model.ModelServiceClient;
import com.flowagent.engine.integration.model.bo.LlmReqBo;
import com.flowagent.engine.integration.model.bo.LlmResVo;
import com.flowagent.engine.node.FlowEventCallback;
import org.springframework.ai.chat.model.ChatResponse;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * ReAct reasoning loop used by the Agent node.
 *
 * <p>Drives an LLM in a Think-Act-Observe cycle until a final answer is produced or
 * {@code maxIter} is reached. The "tools" are workflow nodes: the loop asks the LLM which
 * referenced node to call and with what arguments, then the supplied {@link BiFunction}
 * invokes that node and returns its outputs as the observation.
 *
 * <p>Each iteration emits a {@code workflow_step} SSE event carrying thought / action /
 * observation so the run is fully visible in the UI.
 */
public class ReActLoop {

    private final String agentNodeId;
    private final String goal;
    private final ModelServiceClient modelServiceClient;
    private final String modelId;
    private final String systemTemplate;
    private final List<ToolSpec> tools;
    private final WorkflowContextStore variablePool;
    private final FlowEventCallback callback;
    private final int maxIter;
    private final BiFunction<String, Map<String, Object>, Map<String, Object>> toolInvoker;

    public ReActLoop(String agentNodeId, String goal, ModelServiceClient modelServiceClient,
                     String modelId, String systemTemplate, List<ToolSpec> tools,
                     WorkflowContextStore variablePool, FlowEventCallback callback, int maxIter,
                     BiFunction<String, Map<String, Object>, Map<String, Object>> toolInvoker) {
        this.agentNodeId = agentNodeId;
        this.goal = goal;
        this.modelServiceClient = modelServiceClient;
        this.modelId = modelId;
        this.systemTemplate = systemTemplate;
        this.tools = tools;
        this.variablePool = variablePool;
        this.callback = callback;
        this.maxIter = Math.max(1, maxIter);
        this.toolInvoker = toolInvoker;
    }

    /**
     * Run the loop to completion.
     *
     * @return the final answer (or a fallback message if the iteration budget is exhausted)
     */
    public ReActResult run() {
        StringBuilder history = new StringBuilder();
        List<Map<String, Object>> steps = new ArrayList<>();
        String finalAnswer = null;
        int iteration = 0;

        for (; iteration < maxIter; iteration++) {
            String prompt = buildPrompt(history.toString());
            LlmReqBo req = new LlmReqBo();
            req.setNodeId(agentNodeId);
            req.setModelId(modelId);
            req.setSystemMsg(systemTemplate);
            req.setUserMsg(prompt);

            LlmResVo llmOutput = modelServiceClient.chatCompletion(req, (ChatResponse r) -> {
            });
            String content = llmOutput == null ? "" : llmOutput.content();
            if (content == null) {
                content = "";
            }

            Action action = parseAction(content);
            if (action.isFinal()) {
                finalAnswer = action.getAnswer();
                emitStep(iteration, action.getThought(), "final", "");
                steps.add(stepRecord(iteration, action.getThought(), "final", ""));
                break;
            }

            emitStep(iteration, action.getThought(), "action:" + action.getNodeId(), null);
            steps.add(stepRecord(iteration, action.getThought(), "action:" + action.getNodeId(), null));

            Map<String, Object> observation = toolInvoker.apply(action.getNodeId(), action.getArgs());
            String observationText = observation == null ? "null" : JSON.toJSONString(observation);

            emitStep(iteration, action.getThought(), "action:" + action.getNodeId(), observationText);
            steps.add(stepRecord(iteration, action.getThought(), "action:" + action.getNodeId(), observationText));

            history.append("Thought: ").append(action.getThought()).append("\n")
                    .append("Action: call ").append(action.getNodeId()).append(" with ").append(JSON.toJSONString(action.getArgs())).append("\n")
                    .append("Observation: ").append(observationText).append("\n");
        }

        if (finalAnswer == null) {
            finalAnswer = "Reached max iterations (" + maxIter + ") without a final answer.";
        }

        // Persist the reasoning trace for downstream inspection / UI rendering.
        variablePool.set(agentNodeId, "scratchpad", steps);
        return new ReActResult(finalAnswer, iteration);
    }

    private void emitStep(int iteration, String thought, String action, String observation) {
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("nodeId", agentNodeId);
        ev.put("iteration", iteration);
        ev.put("thought", thought);
        ev.put("action", action);
        ev.put("observation", observation);
        callback.callback("workflow_step", ev);
    }

    private Map<String, Object> stepRecord(int iteration, String thought, String action, String observation) {
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("iteration", iteration);
        ev.put("thought", thought);
        ev.put("action", action);
        ev.put("observation", observation);
        return ev;
    }

    private Action parseAction(String content) {
        String trimmed = content.trim();
        // Strip optional markdown code fences around the JSON payload.
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            int lastFence = trimmed.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                trimmed = trimmed.substring(firstNewline + 1, lastFence).trim();
            }
        }
        try {
            JSONObject jo = JSON.parseObject(trimmed);
            if (jo != null && jo.containsKey("type")) {
                String type = jo.getString("type");
                String thought = jo.getString("thought");
                if ("final".equals(type)) {
                    return new Action(true, thought, jo.getString("answer"), null, null);
                }
                String nodeId = jo.getString("nodeId");
                Map<String, Object> args = jo.getObject("args", Map.class);
                if (args == null) {
                    args = Map.of();
                }
                return new Action(false, thought, null, nodeId, args);
            }
        } catch (Exception ignored) {
            // Fall through to treating the raw text as a final answer.
        }
        // No parseable JSON: treat the whole model output as the final answer so the
        // agent always terminates.
        return new Action(true, "", content, null, null);
    }

    private String buildPrompt(String history) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are an orchestration agent embedded in a workflow engine. Achieve the GOAL ")
                .append("by calling the available workflow nodes (tools) when you need data, ")
                .append("then return a final answer.\n\n");
        sb.append("Available tools (invoke by returning the nodeId in JSON):\n");
        if (tools.isEmpty()) {
            sb.append("(none)\n");
        } else {
            for (ToolSpec t : tools) {
                sb.append("- ").append(t.getNodeId()).append(": ").append(t.getDescription()).append("\n");
            }
        }
        sb.append("\nRespond with EXACTLY ONE JSON object and nothing else:\n");
        sb.append("{\"type\":\"action\",\"thought\":\"...\",\"nodeId\":\"<tool nodeId>\",\"args\":{...}}\n");
        sb.append("or when finished:\n");
        sb.append("{\"type\":\"final\",\"thought\":\"...\",\"answer\":\"...\"}\n\n");
        sb.append("GOAL: ").append(goal).append("\n");
        if (!history.isEmpty()) {
            sb.append("\nPREVIOUS STEPS:\n").append(history);
        }
        return sb.toString();
    }

    /** Description of a tool (referenced workflow node) the agent may invoke. */
    public static class ToolSpec {
        private final String nodeId;
        private final String description;

        public ToolSpec(String nodeId, String description) {
            this.nodeId = nodeId;
            this.description = description;
        }

        public String getNodeId() {
            return nodeId;
        }

        public String getDescription() {
            return description;
        }
    }

    /** Parsed model decision for one ReAct iteration. */
    public static class Action {
        private final boolean fin;
        private final String thought;
        private final String answer;
        private final String nodeId;
        private final Map<String, Object> args;

        public Action(boolean fin, String thought, String answer, String nodeId, Map<String, Object> args) {
            this.fin = fin;
            this.thought = thought;
            this.answer = answer;
            this.nodeId = nodeId;
            this.args = args;
        }

        public boolean isFinal() {
            return fin;
        }

        public String getThought() {
            return thought;
        }

        public String getAnswer() {
            return answer;
        }

        public String getNodeId() {
            return nodeId;
        }

        public Map<String, Object> getArgs() {
            return args;
        }
    }

    /** Result of a completed ReAct loop. */
    public record ReActResult(String answer, int iterations) {
    }
}
