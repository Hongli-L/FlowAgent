package com.flowagent.engine.dsl;

import com.flowagent.engine.WorkflowContextStore;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Evaluates boolean condition expressions used by branch nodes (IF_ELSE / CONDITION_SWITCH).
 *
 * <p>Supported comparison operators: {@code == != > < >= <= contains}
 * Logical combinators: {@code &&} (higher precedence) and {@code ||} (lower precedence).
 * Variable references use the {@code {{node-id.field}}} syntax and are resolved from the
 * workflow context store (the variable pool holding upstream node outputs).
 * Literals: double/single quoted strings, numbers, and the keywords true/false.
 *
 * <p>Examples:
 * <pre>
 *   {{node-start::001.user_input}} contains "refund"
 *   {{llm::002.score}} >= 60 && {{llm::002.score}} < 90
 *   {{switch::003.mode}} == "a" || {{switch::003.mode}} == "b"
 * </pre>
 */
public final class ConditionEvaluator {

    private static final Pattern REF_PATTERN = Pattern.compile("\\{\\{([^}]+)\\}\\}");

    private ConditionEvaluator() {
    }

    public static boolean evaluate(String expression, WorkflowContextStore context) {
        if (expression == null || expression.isBlank()) {
            return false;
        }
        String trimmed = expression.trim();
        if (trimmed.equalsIgnoreCase("true")) {
            return true;
        }
        if (trimmed.equalsIgnoreCase("false")) {
            return false;
        }
        // OR has lower precedence than AND
        String[] orParts = splitTopLevel(trimmed, "||");
        for (String orPart : orParts) {
            if (evaluateAnd(orPart, context)) {
                return true;
            }
        }
        return false;
    }

    private static boolean evaluateAnd(String expr, WorkflowContextStore context) {
        String[] andParts = splitTopLevel(expr, "&&");
        for (String andPart : andParts) {
            if (!evaluateClause(andPart, context)) {
                return false;
            }
        }
        return true;
    }

    private static boolean evaluateClause(String clause, WorkflowContextStore context) {
        String trimmed = clause.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        Operator op = findOperator(trimmed);
        if (op == null) {
            // Bare reference or literal treated as a boolean value
            return isTruthy(resolveOperand(trimmed, context));
        }
        String leftToken = trimmed.substring(0, op.index()).trim();
        String rightToken = trimmed.substring(op.index() + op.symbol().length()).trim();
        Object left = resolveOperand(leftToken, context);
        Object right = resolveOperand(rightToken, context);
        return compare(left, op.symbol(), right);
    }

    private static boolean compare(Object left, String op, Object right) {
        boolean leftNum = isNumeric(left);
        boolean rightNum = isNumeric(right);
        if (leftNum && rightNum) {
            int cmp = new BigDecimal(left.toString()).compareTo(new BigDecimal(right.toString()));
            return switch (op) {
                case "==" -> cmp == 0;
                case "!=" -> cmp != 0;
                case ">" -> cmp > 0;
                case "<" -> cmp < 0;
                case ">=" -> cmp >= 0;
                case "<=" -> cmp <= 0;
                default -> false;
            };
        }
        if ("contains".equals(op)) {
            return String.valueOf(left).contains(String.valueOf(right));
        }
        String ls = left == null ? "" : left.toString();
        String rs = right == null ? "" : right.toString();
        if ("==".equals(op)) {
            return ls.equals(rs);
        }
        if ("!=".equals(op)) {
            return !ls.equals(rs);
        }
        int cmp = ls.compareTo(rs);
        return switch (op) {
            case ">" -> cmp > 0;
            case "<" -> cmp < 0;
            case ">=" -> cmp >= 0;
            case "<=" -> cmp <= 0;
            default -> false;
        };
    }

    private static boolean isTruthy(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof Number n) {
            return n.doubleValue() != 0;
        }
        if (value instanceof String s) {
            return !s.isEmpty() && !"false".equalsIgnoreCase(s);
        }
        return true;
    }

    private static Object resolveOperand(String token, WorkflowContextStore context) {
        if (token == null || token.isBlank()) {
            return null;
        }
        String t = token.trim();
        Matcher m = REF_PATTERN.matcher(t);
        if (m.matches()) {
            return resolveReference(m.group(1).trim(), context);
        }
        if ((t.startsWith("\"") && t.endsWith("\"")) || (t.startsWith("'") && t.endsWith("'"))) {
            return t.substring(1, t.length() - 1);
        }
        if ("true".equalsIgnoreCase(t)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(t)) {
            return Boolean.FALSE;
        }
        if (isNumeric(t)) {
            return new BigDecimal(t);
        }
        return t;
    }

    private static Object resolveReference(String reference, WorkflowContextStore context) {
        int dot = reference.indexOf('.');
        if (dot < 0) {
            // Whole node output map referenced
            return context.get(reference);
        }
        String nodeId = reference.substring(0, dot);
        String path = reference.substring(dot + 1);
        return context.get(nodeId, path);
    }

    private static boolean isNumeric(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Number) {
            return true;
        }
        if (value instanceof Boolean) {
            return false;
        }
        String s = value.toString().trim();
        if (s.isEmpty()) {
            return false;
        }
        try {
            new BigDecimal(s);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * Split on a top-level delimiter, ignoring delimiters that appear inside quotes.
     */
    private static String[] splitTopLevel(String expr, String delimiter) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingle = false;
        boolean inDouble = false;
        int i = 0;
        int n = expr.length();
        int dlen = delimiter.length();
        while (i < n) {
            char c = expr.charAt(i);
            if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
                current.append(c);
                i++;
            } else if (c == '"' && !inSingle) {
                inDouble = !inDouble;
                current.append(c);
                i++;
            } else if (!inSingle && !inDouble
                    && i + dlen <= n && expr.substring(i, i + dlen).equals(delimiter)) {
                parts.add(current.toString());
                current.setLength(0);
                i += dlen;
            } else {
                current.append(c);
                i++;
            }
        }
        parts.add(current.toString());
        return parts.toArray(new String[0]);
    }

    /**
     * Scan char-by-char (skipping quoted regions) for the first comparison operator.
     */
    private static Operator findOperator(String s) {
        boolean inSingle = false;
        boolean inDouble = false;
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
                i++;
                continue;
            }
            if (c == '"' && !inSingle) {
                inDouble = !inDouble;
                i++;
                continue;
            }
            if (inSingle || inDouble) {
                i++;
                continue;
            }
            for (String sym : new String[]{"==", "!=", ">=", "<=", ">", "<"}) {
                if (i + sym.length() <= n && s.substring(i, i + sym.length()).equals(sym)) {
                    return new Operator(sym, i);
                }
            }
            if (i + 8 <= n && s.substring(i, i + 8).equalsIgnoreCase("contains")) {
                boolean beforeOk = i == 0 || Character.isWhitespace(s.charAt(i - 1));
                int after = i + 8;
                boolean afterOk = after >= n || Character.isWhitespace(s.charAt(after));
                if (beforeOk && afterOk) {
                    return new Operator("contains", i);
                }
            }
            i++;
        }
        return null;
    }

    private record Operator(String symbol, int index) {
    }
}
