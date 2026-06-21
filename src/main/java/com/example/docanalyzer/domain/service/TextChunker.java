package com.example.docanalyzer.domain.service;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits long text into overlapping windows for chunked LLM analysis.
 */
public final class TextChunker {

    private TextChunker() {
    }

    /**
     * Slides a window of {@code chunkSize} chars across {@code text}, advancing
     * by {@code chunkSize - overlap} each step so consecutive chunks share
     * {@code overlap} chars of context.
     */
    public static List<String> chunk(String text, int chunkSize, int overlap) {
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("chunkSize must be > 0");
        }
        if (overlap < 0 || overlap >= chunkSize) {
            throw new IllegalArgumentException("overlap must be in [0, chunkSize)");
        }
        if (text == null || text.isEmpty()) return List.of();
        if (text.length() <= chunkSize) return List.of(text);

        List<String> chunks = new ArrayList<>();
        int step = chunkSize - overlap;
        for (int pos = 0; pos < text.length(); pos += step) {
            int end = Math.min(pos + chunkSize, text.length());
            chunks.add(text.substring(pos, end));
            if (end == text.length()) break;
        }
        return chunks;
    }
}
