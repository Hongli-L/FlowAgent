package com.flowagent.engine.integration.model;

import com.flowagent.common.exception.ModelInvocationException;

/**
 * Decides whether a failed model attempt should trigger fallback to the next endpoint.
 *
 * <p>Only recoverable model-call failures fall back. Node configuration errors
 * (e.g. missing template, invalid modelId) are {@link IllegalArgumentException} thrown before any
 * model call and must NOT fall back &mdash; they are permanent and retrying on another model would
 * just fail the same way.</p>
 */
public final class ModelFallbackPolicy {

    private ModelFallbackPolicy() {
    }

    public static boolean isFallbackEligible(Throwable failure) {
        return failure instanceof ModelInvocationException mie && mie.isRecoverable();
    }
}
