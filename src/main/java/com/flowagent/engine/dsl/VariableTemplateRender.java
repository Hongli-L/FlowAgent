package com.flowagent.engine.dsl;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class VariableTemplateRender {

    private static final Pattern VARIABLE_PATTERN = Pattern.compile("\\{\\{([^}]+)\\}\\}");

    private VariableTemplateRender() {
    }

    public static String render(String template, Map<String, Object> inputs) {
        if (template == null || template.isEmpty()) {
            return template;
        }

        Matcher matcher = VARIABLE_PATTERN.matcher(template);
        StringBuilder result = new StringBuilder();

        while (matcher.find()) {
            String reference = matcher.group(1).trim();
            Object value = resolveReference(reference, inputs);
            if (value == null) {
                continue;
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(String.valueOf(value)));
        }

        matcher.appendTail(result);
        return result.toString();
    }

    private static Object resolveReference(String reference, Map<String, Object> inputs) {
        int dotIndex = reference.indexOf('.');
        if (dotIndex < 0) {
            return inputs.get(reference);
        }

        String rootKey = reference.substring(0, dotIndex);
        String nestedPath = reference.substring(dotIndex + 1);
        Object rootValue = inputs.get(rootKey);
        if (rootValue == null) {
            return null;
        }
        return VariablePathResolver.resolve(rootValue, nestedPath);
    }
}
