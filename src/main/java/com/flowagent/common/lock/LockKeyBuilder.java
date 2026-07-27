package com.flowagent.common.lock;

import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.ParserContext;
import org.springframework.expression.common.TemplateParserContext;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;

import java.lang.reflect.Method;

/**
 * Resolves a distributed-lock key from a SpEL expression bound to the annotated method arguments.
 *
 * <p>Variables are exposed by parameter name, so {@code key = "#workflowId"} reads the method
 * argument named {@code workflowId}. Both plain SpEL ({@code #id}) and template syntax
 * ({@code user:#{#id}}) are supported; when no expression is given the method name is used.</p>
 */
public final class LockKeyBuilder {

    private static final ExpressionParser PARSER = new SpelExpressionParser();
    private static final DefaultParameterNameDiscoverer DISCOVERER = new DefaultParameterNameDiscoverer();
    private static final ParserContext TEMPLATE_CONTEXT = new TemplateParserContext();

    private LockKeyBuilder() {
    }

    public static String build(String prefix, String keyExpression, Method method, Object[] args) {
        if (keyExpression == null || keyExpression.isBlank()) {
            return prefix + method.getName();
        }
        EvaluationContext context = new StandardEvaluationContext();
        String[] paramNames = DISCOVERER.getParameterNames(method);
        if (paramNames != null) {
            for (int i = 0; i < paramNames.length && i < args.length; i++) {
                context.setVariable(paramNames[i], args[i]);
            }
        }
        try {
            if (keyExpression.contains("#{")) {
                // Template form: prefix + expression is a single template with #{...} delimiters.
                String template = prefix + keyExpression;
                Expression expression = PARSER.parseExpression(template, TEMPLATE_CONTEXT);
                Object value = expression.getValue(context);
                return value == null ? template : value.toString();
            }
            // Plain form: evaluate the expression alone, then prepend the prefix.
            Expression expression = PARSER.parseExpression(keyExpression);
            Object value = expression.getValue(context);
            return prefix + (value == null ? "" : value.toString());
        } catch (Exception e) {
            String fullKey = prefix + keyExpression;
            throw new DistributedLockException(fullKey,
                    DistributedLockException.LockErrorType.KEY_PARSE_FAILED,
                    "Failed to parse lock key expression: " + fullKey, e);
        }
    }
}
