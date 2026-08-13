package com.flowagent.engine.integration.model;

import com.flowagent.common.enums.MsgTypeEnum;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link LlmChatHistory} token-budget sliding window.
 *
 * This is the mechanism behind the resume bullet: "Token 预算滑动窗口 + Guava 本地缓存
 * 完成长链路历史压缩，规避 Token 暴胀与全局 OOM". The sliding window is applied at read
 * time by {@link LlmChatHistory#getHistoryByTokenBudget(String, String)}: when the
 * estimated token count of all stored rounds exceeds the budget, the oldest chat
 * rounds are dropped while always retaining at least one recent round.
 *
 * NOTE: maxContextTokens is a static field on LlmChatHistory, so every test resets
 * it to the default (8192) in tearDown to avoid cross-test contamination.
 */
class LlmChatHistoryTest {

    private static final int DEFAULT_BUDGET = 8192;
    private String lastChatId;
    private String lastNodeId;

    @AfterEach
    void tearDown() {
        if (lastChatId != null && lastNodeId != null) {
            LlmChatHistory.clearHistory(lastChatId, lastNodeId);
        }
        // Restore the default budget so other tests / production code are unaffected.
        LlmChatHistory.setMaxContextTokens(DEFAULT_BUDGET);
    }

    /**
     * Default budget must match the engine configuration (EngineProperties.maxContextTokens).
     */
    @Test
    void defaultTokenBudget_shouldMatchEngineConfig() {
        assertEquals(DEFAULT_BUDGET, LlmChatHistory.getMaxContextTokens());
    }

    /**
     * When total estimated tokens exceed the budget, the sliding window drops the
     * oldest rounds while keeping the most recent ones, so the recent context is
     * preserved and the token count is bounded (prevents unlimited growth / OOM).
     */
    @Test
    void slidingWindow_dropsOldestRounds_whenOverBudget() {
        // Tight budget: a single round (user + assistant, ~93 chars each) is ~64 tokens,
        // so 5 rounds (~320 tokens) must be trimmed down to fit 100.
        LlmChatHistory.setMaxContextTokens(100);
        String chatId = "budget-overflow";
        String nodeId = "llm::window";
        lastChatId = chatId;
        lastNodeId = nodeId;

        int rounds = 5;
        for (int i = 0; i < rounds; i++) {
            LlmChatHistory.newChat(chatId, nodeId);
            LlmChatHistory.addMessage(chatId, nodeId, MsgTypeEnum.USER, "u" + i + " " + repeat('x', 90));
            LlmChatHistory.addMessage(chatId, nodeId, MsgTypeEnum.ASSISTANT, "a" + i + " " + repeat('x', 90));
        }

        // All 5 rounds are still stored in the underlying cache.
        List<LlmChatHistory.ChatItem> all = LlmChatHistory.getHistory(chatId, nodeId);
        assertEquals(rounds, all.size(), "all rounds remain in the cache");

        // But the token-budgeted view must drop the oldest rounds.
        List<LlmChatHistory.ChatItem> budgeted = LlmChatHistory.getHistoryByTokenBudget(chatId, nodeId);
        assertTrue(budgeted.size() < rounds,
                "sliding window should drop oldest rounds when over budget");
        assertTrue(budgeted.size() >= 1, "at least one recent round must always be retained");

        // Most recent round (round 4) is preserved.
        LlmChatHistory.ChatItem latest = budgeted.get(budgeted.size() - 1);
        assertTrue(latest.userInputs().get(0).content().contains("u4"),
                "most recent round must be kept by the sliding window");

        // Oldest round (round 0) is dropped.
        boolean containsRound0 = budgeted.stream()
                .flatMap(it -> it.userInputs().stream())
                .anyMatch(m -> m.content().contains("u0"));
        assertFalse(containsRound0, "oldest round should be evicted by the sliding window");
    }

    /**
     * Even if a single round alone exceeds the budget, the window must never drop
     * below one item. This guarantees the LLM always receives at least the latest
     * context (avoids empty-prompt failures).
     */
    @Test
    void slidingWindow_keepsAtLeastOneRound_evenWhenSingleRoundOverBudget() {
        // Budget far smaller than a single round's tokens.
        LlmChatHistory.setMaxContextTokens(10);
        String chatId = "single-over-budget";
        String nodeId = "llm::single";
        lastChatId = chatId;
        lastNodeId = nodeId;

        LlmChatHistory.newChat(chatId, nodeId);
        LlmChatHistory.addMessage(chatId, nodeId, MsgTypeEnum.USER, repeat('x', 300));
        LlmChatHistory.addMessage(chatId, nodeId, MsgTypeEnum.ASSISTANT, repeat('x', 300));

        List<LlmChatHistory.ChatItem> budgeted = LlmChatHistory.getHistoryByTokenBudget(chatId, nodeId);
        assertEquals(1, budgeted.size(),
                "sliding window must retain at least one round even if it alone exceeds the budget");
    }

    /**
     * Under budget the full history is returned untouched (no spurious eviction).
     */
    @Test
    void slidingWindow_returnsFullHistory_whenUnderBudget() {
        LlmChatHistory.setMaxContextTokens(10000);
        String chatId = "under-budget";
        String nodeId = "llm::under";
        lastChatId = chatId;
        lastNodeId = nodeId;

        int rounds = 4;
        for (int i = 0; i < rounds; i++) {
            LlmChatHistory.newChat(chatId, nodeId);
            LlmChatHistory.addMessage(chatId, nodeId, MsgTypeEnum.USER, "hello-" + i);
            LlmChatHistory.addMessage(chatId, nodeId, MsgTypeEnum.ASSISTANT, "world-" + i);
        }

        List<LlmChatHistory.ChatItem> budgeted = LlmChatHistory.getHistoryByTokenBudget(chatId, nodeId);
        assertEquals(rounds, budgeted.size(), "no eviction when under budget");

        // The oldest round must be present (nothing dropped).
        boolean containsRound0 = budgeted.stream()
                .flatMap(it -> it.userInputs().stream())
                .anyMatch(m -> m.content().contains("hello-0"));
        assertTrue(containsRound0, "oldest round must be kept when under budget");
    }

    private static String repeat(char c, int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append(c);
        }
        return sb.toString();
    }
}
