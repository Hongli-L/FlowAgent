package com.flowagent.engine.dsl;

import java.util.List;
import java.util.Map;

public final class VariablePathResolver {

    private VariablePathResolver() {
    }

    @SuppressWarnings("unchecked")
    public static Object resolve(Object root, String path) {
        if (root == null || path == null || path.isBlank()) {
            return root;
        }
        if (root instanceof Map<?, ?> map) {
            return resolveMap((Map<String, Object>) map, path);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Object resolveMap(Map<String, Object> map, String key) {
        int index = key.indexOf('.');
        if (index < 0) {
            return resolveSegment(map, key);
        }

        String rootKey = key.substring(0, index);
        String subKey = key.substring(index + 1);
        Object next = resolveSegment(map, rootKey);
        if (next instanceof Map<?, ?> nextMap) {
            return resolveMap((Map<String, Object>) nextMap, subKey);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Object resolveSegment(Map<String, Object> map, String segment) {
        int bracketStart = segment.indexOf('[');
        if (bracketStart > 0 && segment.endsWith("]")) {
            String listKey = segment.substring(0, bracketStart);
            String indexText = segment.substring(bracketStart + 1, segment.length() - 1);
            Object listValue = map.get(listKey);
            if (!(listValue instanceof List<?> list)) {
                return null;
            }
            int listIndex = Integer.parseInt(indexText);
            if (listIndex < 0 || listIndex >= list.size()) {
                return null;
            }
            return list.get(listIndex);
        }
        return map.get(segment);
    }
}
