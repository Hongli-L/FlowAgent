package com.flowagent.engine.integration.model;

import com.flowagent.common.exception.ModelInvocationException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ModelFallbackPolicyTest {

    @Test
    void recoverableModelFailureIsEligible() {
        assertTrue(ModelFallbackPolicy.isFallbackEligible(
                new ModelInvocationException("model 500", null, 500, true)));
    }

    @Test
    void nonRecoverableModelFailureIsNotEligible() {
        assertFalse(ModelFallbackPolicy.isFallbackEligible(
                new ModelInvocationException("permanent", null, 400, false)));
    }

    @Test
    void configErrorIsNotEligible() {
        assertFalse(ModelFallbackPolicy.isFallbackEligible(
                new IllegalArgumentException("missing template")));
    }

    @Test
    void nonModelExceptionIsNotEligible() {
        assertFalse(ModelFallbackPolicy.isFallbackEligible(new RuntimeException("boom")));
    }
}
