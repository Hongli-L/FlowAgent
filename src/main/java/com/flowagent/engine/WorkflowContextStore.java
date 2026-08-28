package com.flowagent.engine;

import cn.hutool.core.util.ClassUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ConcurrentHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Instance-level variable pool for managing node outputs and resolving template references.
 * Each workflow execution creates its own WorkflowContextStore instance, avoiding global OOM risk.
 * Handles variable references in format: {{node-id.output-name}}
 * <p>
 * Example:
 * - Template: "User said: {{node-start::001.user_input}}"
 * - After resolution: "User said: tell me about Java"
 */
@Slf4j
public class WorkflowContextStore {

    /**
     * Storage for node outputs
     * Key: "node-id.output-name" (e.g., "node-start::001.user_input")
     * Value: actual output value
     */
    private final Map<String, Map<String, Object>> variables = new ConcurrentHashMap<>();

    /**
     * Set a variable in the pool
     *
     * @param nodeId     node ID (e.g., "node-start::001")
     * @param outputName output name (e.g., "user_input")
     * @param value      actual value
     */
    public void set(String nodeId, String outputName, Object value) {
        // Primitive types, Number and String stored directly; complex types (List, Map, Object) converted to JSONObject/JSONArray for nested path resolution
        // JSONArray/JSONObject representation enables dot-path and array-index variable resolution
        if (ClassUtil.isPrimitiveWrapper(value.getClass()) || value instanceof Number || value instanceof String
                || value instanceof JSONArray || value instanceof JSONObject || value instanceof UUID) {

        } else if (value instanceof List<?>) {
            value = JSON.parseArray(JSON.toJSONString(value));
        } else {
            value = JSON.parseObject(JSON.toJSONString(value));
        }

        variables.computeIfAbsent(nodeId, k -> new ConcurrentHashMap<>()).put(outputName, value);
    }

    /**
     * Get a variable from the pool
     *
     * @param nodeId     node ID
     * @param outputName output name
     * @return variable value, or null if not found
     */
    public Object get(String nodeId, String outputName) {
        Map target = variables.getOrDefault(nodeId, Map.of());
        return getVal(target, outputName);
    }

    public Map<String, Object> get(String nodeId) {
        return variables.getOrDefault(nodeId, Map.of());
    }

    /**
     * Get the entire variable pool as a flat context map: node-id -> (output-name -> value).
     * Used by the End node to render {{node-id.field}} templates against ALL upstream outputs,
     * not just the (typically empty) inputs flowing into the End node.
     */
    public Map<String, Map<String, Object>> getAll() {
        return new HashMap<>(variables);
    }


    /**
     * Nested value extraction
     *
     * @param map  target map to resolve from
     * @param key  dot-path key, e.g. data.voice_url resolves nested map access;
     *              data[0].voice_url resolves list element then nested map access
     * @return resolved value
     */
    @SuppressWarnings("unchecked")
    private Object getVal(Map map, String key) {
        int index = key.indexOf(".");
        if (index < 0) {
            return map.get(key);
        }

        String rootKey = key.substring(0, index);
        String subKey = key.substring(index + 1);

        Map subMap;

        // If rootKey contains array index like xxx[0], further extraction is needed
        index = rootKey.indexOf("[");
        if (index > 0 && rootKey.endsWith("]")) {
            String subIndex = rootKey.substring(index + 1, rootKey.length() - 1);
            rootKey = rootKey.substring(0, index);

            subMap = (Map) ((List) map.get(rootKey)).get(Integer.parseInt(subIndex));
        } else {
            subMap = (Map) map.get(rootKey);
        }


        return getVal(subMap, subKey);
    }

    /**
     * Clear all variables (used between workflow executions)
     */
    public void clear() {
        variables.clear();
    }
}
