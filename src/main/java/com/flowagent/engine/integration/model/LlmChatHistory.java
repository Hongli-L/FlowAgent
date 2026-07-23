package com.flowagent.engine.integration.model;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.flowagent.common.enums.MsgTypeEnum;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

/**
 * LLM chat history cache.
 * Key: ChatId + NodeId combination
 * Value: fixed-length conversation history list
 *
 * Sliding-window strategy: when estimated tokens exceed the configured budget,
 * the oldest messages are discarded, retaining system prompt + recent N rounds.
 */
public class LlmChatHistory {

    /**
     * Chat history cache
     * Key format: "{chatId}:{nodeId}"
     * Value: fixed-length history queue
     */
    private static final LoadingCache<String, ConcurrentLinkedQueue<ChatItem>> chatHistoryCache =
            CacheBuilder.newBuilder()
                    .maximumSize(10000) // max cache 10000 sessions
                    .expireAfterWrite(30, TimeUnit.MINUTES) // expire after 30 minutes
                    .build(CacheLoader.from(LlmChatHistory::createChatHistoryQueue));

    /**
     * Max history length per session
     */
    private static final int MAX_HISTORY_LENGTH = 10;

    /**
     * Maximum token budget for LLM context window.
     * Sliding-window: drop oldest messages when estimated tokens exceed this limit.
     */
    private static int maxContextTokens = 8192;

    /**
     * Rough token estimation factor: ~4 chars per token for English, ~2 chars per token for Chinese.
     * We use a conservative average of 3 chars/token.
     */
    private static final int CHAR_PER_TOKEN = 3;

    /**
     * Chat message entity using record type
     */
    public record ChatMessage(MsgTypeEnum role, String content, long timestamp) {
        public ChatMessage(MsgTypeEnum role, String content) {
            this(role, content, System.currentTimeMillis());
        }
    }

    /**
     * Chat item entity representing a complete conversation round
     */
    public record ChatItem(
            String chatId,
            String nodeId,
            List<ChatMessage> userInputs,     // User input
            List<ChatMessage> llmThinking,    // LLM thinking process
            List<ChatMessage> llmResponses    // LLM response content
    ) {
        public ChatItem(String chatId, String nodeId) {
            this(chatId, nodeId, new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        }
    }

    /**
     * Create fixed-length chat history queue
     */
    private static ConcurrentLinkedQueue<ChatItem> createChatHistoryQueue() {
        return new ConcurrentLinkedQueue<ChatItem>() {
            @Override
            public boolean add(ChatItem item) {
                // If queue is full, remove oldest record
                if (size() >= MAX_HISTORY_LENGTH) {
                    poll();
                }
                return super.add(item);
            }
        };
    }

    public static void newChat(String chatId, String nodeId) {
        String key = generateKey(chatId, nodeId);
        ConcurrentLinkedQueue<ChatItem> queue = chatHistoryCache.getUnchecked(key);
        ChatItem newItem = new ChatItem(chatId, nodeId);
        queue.add(newItem);
    }

    /**
     * Add chat record
     *
     * @param chatId  Session ID
     * @param nodeId  Node ID
     * @param role    Message role (system/user/assistant)
     * @param content Message content
     */
    public static void addMessage(String chatId, String nodeId, MsgTypeEnum role, String content) {
        String key = generateKey(chatId, nodeId);
        ChatMessage message = new ChatMessage(role, content);

        // Get or create current ChatItem
        ConcurrentLinkedQueue<ChatItem> queue = chatHistoryCache.getUnchecked(key);
        ChatItem currentItem = getCurrentOrNewChatItem(queue, chatId, nodeId);

        // Add message to corresponding list by role type
        switch (role) {
            case USER:
            case SYSTEM:
                currentItem.userInputs().add(message);
                break;
            case THINKING:
                currentItem.llmThinking().add(message);
                break;
            case ASSISTANT:
                currentItem.llmResponses().add(message);
                break;
        }
    }

    /**
     * Get current or new ChatItem
     */
    private static ChatItem getCurrentOrNewChatItem(ConcurrentLinkedQueue<ChatItem> queue,
                                                    String chatId, String nodeId) {
        // Get the last element from queue
        ChatItem latestItem = null;
        for (ChatItem item : queue) {
            latestItem = item;
        }

        if (latestItem == null) {
            ChatItem newItem = new ChatItem(chatId, nodeId);
            queue.add(newItem);
            return newItem;
        }
        return latestItem;
    }

    /**
     * Retrieve chat history
     *
     * @param chatId Session ID
     * @param nodeId Node ID
     * @return Chat history list
     */
    public static List<ChatItem> getHistory(String chatId, String nodeId) {
        String key = generateKey(chatId, nodeId);
        return new ArrayList<>(chatHistoryCache.getUnchecked(key));
    }

    /**
     * Retrieve specified count of chat history
     *
     * @param chatId Session ID
     * @param nodeId Node ID
     * @param count  specified count
     * @return Specified-count chat history list
     */
    public static List<ChatItem> getHistory(String chatId, String nodeId, int count) {
        String key = generateKey(chatId, nodeId);
        ConcurrentLinkedQueue<ChatItem> queue = chatHistoryCache.getUnchecked(key);

        List<ChatItem> allItems = new ArrayList<>(queue);
        int size = allItems.size();
        int fromIndex = Math.max(0, size - count);

        return new ArrayList<>(allItems.subList(fromIndex, size));
    }

    /**
     * Clear chat history for specified session
     *
     * @param chatId Session ID
     * @param nodeId Node ID
     */
    public static void clearHistory(String chatId, String nodeId) {
        String key = generateKey(chatId, nodeId);
        chatHistoryCache.invalidate(key);
    }

    /**
     * Generate cache key
     *
     * @param chatId Session ID
     * @param nodeId Node ID
     * @return Cache key
     */
    private static String generateKey(String chatId, String nodeId) {
        return chatId + ":" + nodeId;
    }

    /**
     * Set max token budget for context window (called from EngineConfiguration).
     */
    public static void setMaxContextTokens(int tokens) {
        maxContextTokens = tokens;
    }

    /**
     * Retrieve chat history with token-budget sliding window.
     * When estimated tokens exceed maxContextTokens, oldest ChatItems are dropped
     * until the budget is satisfied, always retaining at least 1 recent round.
     *
     * @param chatId Session ID
     * @param nodeId Node ID
     * @return Token-budgeted chat history list
     */
    public static List<ChatItem> getHistoryByTokenBudget(String chatId, String nodeId) {
        String key = generateKey(chatId, nodeId);
        ConcurrentLinkedQueue<ChatItem> queue = chatHistoryCache.getUnchecked(key);
        List<ChatItem> allItems = new ArrayList<>(queue);

        // Estimate total tokens across all items
        int totalTokens = estimateTokens(allItems);

        // Sliding-window: drop oldest items while over budget, keep at least 1
        while (totalTokens > maxContextTokens && allItems.size() > 1) {
            ChatItem removed = allItems.remove(0);
            totalTokens -= estimateTokens(removed);
        }

        return allItems;
    }

    /**
     * Estimate tokens for a list of ChatItems.
     */
    private static int estimateTokens(List<ChatItem> items) {
        int total = 0;
        for (ChatItem item : items) {
            total += estimateTokens(item);
        }
        return total;
    }

    /**
     * Estimate tokens for a single ChatItem.
     * Rough estimation: sum all message content lengths / CHAR_PER_TOKEN.
     */
    private static int estimateTokens(ChatItem item) {
        int total = 0;
        total += sumContentTokens(item.userInputs());
        total += sumContentTokens(item.llmThinking());
        total += sumContentTokens(item.llmResponses());
        return total;
    }

    private static int sumContentTokens(List<ChatMessage> messages) {
        int total = 0;
        for (ChatMessage msg : messages) {
            if (msg.content() != null) {
                total += msg.content().length() / CHAR_PER_TOKEN + 1;
            }
        }
        return total;
    }

    /**
     * Get cache instance (for testing or special purposes)
     */
    public static LoadingCache<String, ConcurrentLinkedQueue<ChatItem>> getCache() {
        return chatHistoryCache;
    }

    /**
     * Get max context tokens config (for testing)
     */
    public static int getMaxContextTokens() {
        return maxContextTokens;
    }
}
