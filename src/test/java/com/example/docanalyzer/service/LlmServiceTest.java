package com.example.docanalyzer.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LlmServiceTest {

    @Test
    void extractAnthropicText_returnsTextBlock_whenFirst() {
        Map<String, Object> response = Map.of(
                "content", List.of(Map.of("type", "text", "text", "hello"))
        );

        assertThat(LlmService.extractAnthropicText(response)).isEqualTo("hello");
    }

    @Test
    void extractAnthropicText_skipsNonTextBlocks() {
        // tool_use comes first; older code did `(String) content.get(0).get("text")`
        // and threw ClassCastException / returned null. The text block must win.
        Map<String, Object> response = Map.of(
                "content", List.of(
                        Map.of("type", "tool_use", "name", "calc", "input", Map.of()),
                        Map.of("type", "text", "text", "actual answer")
                )
        );

        assertThat(LlmService.extractAnthropicText(response)).isEqualTo("actual answer");
    }

    @Test
    void extractAnthropicText_returnsEmpty_whenNoTextBlock() {
        Map<String, Object> response = Map.of(
                "content", List.of(Map.of("type", "tool_use", "name", "x"))
        );

        assertThat(LlmService.extractAnthropicText(response)).isEmpty();
    }

    @Test
    void extractAnthropicText_returnsEmpty_whenContentMissingOrWrongShape() {
        assertThat(LlmService.extractAnthropicText(null)).isEmpty();
        assertThat(LlmService.extractAnthropicText(Map.of())).isEmpty();
        assertThat(LlmService.extractAnthropicText(Map.of("content", List.of()))).isEmpty();
        assertThat(LlmService.extractAnthropicText(Map.of("content", "not-a-list"))).isEmpty();
    }
}
