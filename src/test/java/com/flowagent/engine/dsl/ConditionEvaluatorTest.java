package com.flowagent.engine.dsl;

import com.flowagent.engine.WorkflowContextStore;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ConditionEvaluator: operator coverage, precedence and variable resolution.
 */
public class ConditionEvaluatorTest {

    private WorkflowContextStore poolWith(Object... triples) {
        WorkflowContextStore pool = new WorkflowContextStore();
        for (int i = 0; i < triples.length; i += 3) {
            pool.set((String) triples[i], (String) triples[i + 1], triples[i + 2]);
        }
        return pool;
    }

    @Test
    void containsOperator() {
        WorkflowContextStore pool = poolWith("node-start::001", "user_input", "I want a refund");
        assertTrue(ConditionEvaluator.evaluate("{{node-start::001.user_input}} contains \"refund\"", pool));
        assertFalse(ConditionEvaluator.evaluate("{{node-start::001.user_input}} contains \"ship\"", pool));
    }

    @Test
    void numericComparisons() {
        WorkflowContextStore pool = poolWith("llm::002", "score", 75);
        assertTrue(ConditionEvaluator.evaluate("{{llm::002.score}} >= 60", pool));
        assertTrue(ConditionEvaluator.evaluate("{{llm::002.score}} < 90", pool));
        assertFalse(ConditionEvaluator.evaluate("{{llm::002.score}} > 90", pool));
        assertTrue(ConditionEvaluator.evaluate("{{llm::002.score}} == 75", pool));
        assertTrue(ConditionEvaluator.evaluate("{{llm::002.score}} != 80", pool));
    }

    @Test
    void stringEquality() {
        WorkflowContextStore pool = poolWith("s", "mode", "prod");
        assertTrue(ConditionEvaluator.evaluate("{{s.mode}} == \"prod\"", pool));
        assertFalse(ConditionEvaluator.evaluate("{{s.mode}} == \"dev\"", pool));
    }

    @Test
    void logicalAndOrPrecedence() {
        WorkflowContextStore pool = poolWith("a", "x", 1, "b", "y", 2);
        assertTrue(ConditionEvaluator.evaluate("{{a.x}} == 1 && {{b.y}} == 2", pool));
        assertFalse(ConditionEvaluator.evaluate("{{a.x}} == 1 && {{b.y}} == 9", pool));
        assertTrue(ConditionEvaluator.evaluate("{{a.x}} == 9 || {{b.y}} == 2", pool));
        assertFalse(ConditionEvaluator.evaluate("{{a.x}} == 9 || {{b.y}} == 9", pool));
    }

    @Test
    void operatorInsideQuotesIsIgnored() {
        WorkflowContextStore pool = poolWith("s", "text", "a == b");
        assertTrue(ConditionEvaluator.evaluate("{{s.text}} contains \"==\"", pool));
    }

    @Test
    void bareReferenceTruthiness() {
        WorkflowContextStore poolTrue = poolWith("s", "flag", true);
        assertTrue(ConditionEvaluator.evaluate("{{s.flag}}", poolTrue));
        WorkflowContextStore poolFalse = poolWith("s", "flag", false);
        assertFalse(ConditionEvaluator.evaluate("{{s.flag}}", poolFalse));
    }

    @Test
    void blankExpressionIsFalse() {
        WorkflowContextStore pool = new WorkflowContextStore();
        assertFalse(ConditionEvaluator.evaluate("", pool));
        assertFalse(ConditionEvaluator.evaluate("   ", pool));
    }

    @Test
    void literalBooleanExpressions() {
        WorkflowContextStore pool = new WorkflowContextStore();
        assertTrue(ConditionEvaluator.evaluate("true", pool));
        assertFalse(ConditionEvaluator.evaluate("false", pool));
    }

    @Test
    void lessThanOrEqualOperator() {
        WorkflowContextStore pool = poolWith("llm::002", "score", 75);
        assertTrue(ConditionEvaluator.evaluate("{{llm::002.score}} <= 75", pool));
        assertFalse(ConditionEvaluator.evaluate("{{llm::002.score}} <= 74", pool));
        assertTrue(ConditionEvaluator.evaluate("{{llm::002.score}} >= 75", pool));
    }

    @Test
    void mixedAndOrPrecedenceBindsAndTighter() {
        // (x==9 && y==9) || (y==2)  -> OR binds looser, so this is TRUE because y==2
        WorkflowContextStore pool = poolWith("a", "x", 1, "b", "y", 2);
        assertTrue(ConditionEvaluator.evaluate("{{a.x}} == 9 && {{b.y}} == 9 || {{b.y}} == 2", pool));
    }

    @Test
    void unresolvedReferenceIsFalsey() {
        WorkflowContextStore pool = poolWith("a", "x", 1);
        assertFalse(ConditionEvaluator.evaluate("{{missing.node}} == 1", pool));
        assertFalse(ConditionEvaluator.evaluate("{{missing.node}}", pool));
    }

    @Test
    void containsSupportsSingleQuotedLiteral() {
        WorkflowContextStore pool = poolWith("s", "text", "I want a refund");
        assertTrue(ConditionEvaluator.evaluate("{{s.text}} contains 'refund'", pool));
    }
}
