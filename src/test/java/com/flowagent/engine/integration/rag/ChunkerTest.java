package com.flowagent.engine.integration.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for Chunker: overlapping fixed-window splitter used by the RAG ingestion pipeline.
 */
public class ChunkerTest {

    @Test
    void blankTextYieldsEmptyList() {
        assertTrue(new Chunker().chunk("   ").isEmpty());
    }

    @Test
    void shortTextIsSingleChunk() {
        List<String> chunks = new Chunker().chunk("hello world");
        assertEquals(1, chunks.size());
        assertEquals("hello world", chunks.get(0));
    }

    @Test
    void longTextSplitsWithOverlap() {
        Chunker chunker = new Chunker(10, 2);
        String text = "abcdefghijklmnopqrstuvwxyz"; // 26 chars
        List<String> chunks = chunker.chunk(text);
        assertTrue(chunks.size() >= 3, "should split into multiple overlapping windows");
        // Every adjacent pair must overlap by exactly the configured 2 chars.
        for (int i = 0; i < chunks.size() - 1; i++) {
            String cur = chunks.get(i);
            String next = chunks.get(i + 1);
            assertEquals(cur.substring(cur.length() - 2), next.substring(0, 2),
                    "overlap between chunk " + i + " and " + (i + 1));
        }
    }

    @Test
    void negativeOverlapThrows() {
        assertThrows(IllegalArgumentException.class, () -> new Chunker(10, -1));
    }

    @Test
    void overlapEqualToChunkSizeThrows() {
        assertThrows(IllegalArgumentException.class, () -> new Chunker(10, 10));
    }
}
