package com.flowagent.engine.integration.rag;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Fixed-size overlapping text splitter used by the RAG pipeline.
 *
 * <p>Long documents are divided into overlapping windows so a single concept is less likely
 * to be cut at a chunk boundary. Default window is 500 chars with 50 chars of overlap; both
 * are configurable via the constructor.
 */
@Component
public class Chunker {

    private final int chunkSize;
    private final int overlap;

    public Chunker() {
        this(500, 50);
    }

    public Chunker(int chunkSize, int overlap) {
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("chunkSize must be positive");
        }
        if (overlap < 0 || overlap >= chunkSize) {
            throw new IllegalArgumentException("overlap must be in [0, chunkSize)");
        }
        this.chunkSize = chunkSize;
        this.overlap = overlap;
    }

    public List<String> chunk(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String cleaned = text.strip();
        if (cleaned.length() <= chunkSize) {
            return List.of(cleaned);
        }
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < cleaned.length()) {
            int end = Math.min(cleaned.length(), start + chunkSize);
            chunks.add(cleaned.substring(start, end));
            if (end == cleaned.length()) {
                break;
            }
            start = end - overlap;
            if (start <= 0) {
                start = end;
            }
        }
        return chunks;
    }
}
