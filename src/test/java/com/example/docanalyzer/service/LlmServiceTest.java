package com.example.docanalyzer.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class LlmServiceTest {

    // ── extractAnthropicText (pure helper) ───────────────────────────────────

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

    // ── Outgoing HTTP shape (MockWebServer) ──────────────────────────────────

    private MockWebServer server;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void startServer() throws IOException {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void stopServer() throws IOException {
        server.shutdown();
    }

    @Test
    void analyzeText_ollama_postsToGenerateAndReturnsResponseField() throws Exception {
        enqueueJson("{\"response\":\"OLLAMA TEXT\"}");

        LlmService llm = newService("ollama");
        String result = llm.analyzeText("body of the PDF");

        assertThat(result).isEqualTo("OLLAMA TEXT");

        RecordedRequest req = takeRequest();
        assertThat(req.getPath()).isEqualTo("/api/generate");
        assertThat(req.getMethod()).isEqualTo("POST");

        Map<String, Object> body = readJson(req);
        assertThat(body).containsEntry("model", "llama3-text");
        assertThat(body).containsEntry("stream", false);
        assertThat((String) body.get("prompt"))
                .contains("Analyze the following document")
                .contains("body of the PDF");
    }

    @Test
    void analyzeImage_ollama_postsImageAsBase64InImagesArray() throws Exception {
        enqueueJson("{\"response\":\"OLLAMA VISION\"}");

        LlmService llm = newService("ollama");
        byte[] bytes = {0x12, 0x34, 0x56};
        String result = llm.analyzeImage(bytes, "image/png");

        assertThat(result).isEqualTo("OLLAMA VISION");

        RecordedRequest req = takeRequest();
        assertThat(req.getPath()).isEqualTo("/api/generate");

        Map<String, Object> body = readJson(req);
        assertThat(body).containsEntry("model", "llava-vision");
        assertThat(body).containsEntry("stream", false);

        @SuppressWarnings("unchecked")
        List<String> images = (List<String>) body.get("images");
        assertThat(images).containsExactly(Base64.getEncoder().encodeToString(bytes));
    }

    @Test
    void analyzeText_anthropic_postsMessagesAndExtractsTextBlock() throws Exception {
        enqueueJson("{\"content\":[{\"type\":\"text\",\"text\":\"CLAUDE TEXT\"}]}");

        LlmService llm = newService("anthropic");
        String result = llm.analyzeText("body of the PDF");

        assertThat(result).isEqualTo("CLAUDE TEXT");

        RecordedRequest req = takeRequest();
        assertThat(req.getPath()).isEqualTo("/v1/messages");
        assertThat(req.getHeader("x-api-key")).isEqualTo("test-key");
        assertThat(req.getHeader("anthropic-version")).isEqualTo("2023-06-01");

        Map<String, Object> body = readJson(req);
        assertThat(body).containsEntry("model", "claude-test-model");
        assertThat(body).containsEntry("max_tokens", 1024);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) body.get("messages");
        assertThat(messages).hasSize(1);
        Map<String, Object> msg = messages.get(0);
        assertThat(msg).containsEntry("role", "user");
        assertThat((String) msg.get("content"))
                .contains("Analyze the following document")
                .contains("body of the PDF");
    }

    @Test
    void analyzeImage_anthropic_postsImageBlockBeforeTextBlock() throws Exception {
        enqueueJson("{\"content\":[{\"type\":\"text\",\"text\":\"CLAUDE VISION\"}]}");

        LlmService llm = newService("anthropic");
        byte[] bytes = {0x01, 0x02, 0x03};
        String result = llm.analyzeImage(bytes, "image/jpeg");

        assertThat(result).isEqualTo("CLAUDE VISION");

        RecordedRequest req = takeRequest();
        assertThat(req.getPath()).isEqualTo("/v1/messages");

        Map<String, Object> body = readJson(req);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) body.get("messages");
        Map<String, Object> msg = messages.get(0);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) msg.get("content");
        assertThat(content).hasSize(2);

        Map<String, Object> imageBlock = content.get(0);
        assertThat(imageBlock).containsEntry("type", "image");
        @SuppressWarnings("unchecked")
        Map<String, Object> source = (Map<String, Object>) imageBlock.get("source");
        assertThat(source).containsEntry("type", "base64");
        assertThat(source).containsEntry("media_type", "image/jpeg");
        assertThat(source).containsEntry("data", Base64.getEncoder().encodeToString(bytes));

        Map<String, Object> textBlock = content.get(1);
        assertThat(textBlock).containsEntry("type", "text");
        assertThat((String) textBlock.get("text")).contains("Analyze the following document");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void enqueueJson(String body) {
        server.enqueue(new MockResponse()
                .setBody(body)
                .addHeader("Content-Type", "application/json"));
    }

    private RecordedRequest takeRequest() throws InterruptedException {
        RecordedRequest req = server.takeRequest(5, TimeUnit.SECONDS);
        assertThat(req).isNotNull();
        return req;
    }

    private Map<String, Object> readJson(RecordedRequest req) throws IOException {
        return mapper.readValue(req.getBody().readUtf8(), new TypeReference<>() {});
    }

    /**
     * Both base URLs point at the same MockWebServer; the active provider
     * decides which one is actually hit. Distinct model names per role let
     * the assertions also catch text-vs-vision mixups.
     */
    private LlmService newService(String provider) {
        String baseUrl = server.url("/").toString();
        return new LlmService(
                provider,
                Duration.ofSeconds(2),
                Duration.ofSeconds(5),
                baseUrl,
                "llava-vision",
                "llama3-text",
                baseUrl,
                "test-key",
                "claude-test-model"
        );
    }
}
