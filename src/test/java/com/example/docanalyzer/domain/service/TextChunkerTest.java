package com.example.docanalyzer.domain.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TextChunkerTest {

    @Test
    void chunk_shorterThanChunkSize_returnsSingleChunk() {
        List<String> chunks = TextChunker.chunk("hello", 100, 10);
        assertThat(chunks).containsExactly("hello");
    }

    @Test
    void chunk_emptyOrNull_returnsEmpty() {
        assertThat(TextChunker.chunk(null, 100, 10)).isEmpty();
        assertThat(TextChunker.chunk("", 100, 10)).isEmpty();
    }

    @Test
    void chunk_longerThanChunkSize_splitsWithOverlap() {
        // 30 chars, chunk=10, overlap=2 → step=8, so chunks start at 0, 8, 16, 24.
        String text = "abcdefghijklmnopqrstuvwxyz1234";
        List<String> chunks = TextChunker.chunk(text, 10, 2);

        assertThat(chunks).containsExactly(
                "abcdefghij",   // 0..10
                "ijklmnopqr",   // 8..18  — first 2 chars overlap with previous
                "qrstuvwxyz",   // 16..26
                "yz1234"        // 24..30 — short tail
        );
    }

    @Test
    void chunk_invalidConfig_throws() {
        assertThatThrownBy(() -> TextChunker.chunk("x", 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TextChunker.chunk("x", 10, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TextChunker.chunk("x", 10, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
