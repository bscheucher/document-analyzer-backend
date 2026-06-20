package com.example.docanalyzer.service;

import io.netty.channel.ChannelOption;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Thin wrapper around Ollama (default) or Anthropic API.
 * Controlled by app.ai.provider in application.yml.
 */
@Slf4j
@Service
public class LlmService {

    private static final String ANALYSIS_PROMPT = """
            Analyze the following document and respond in JSON with this exact structure:
            {
              "documentType": "<type of document, e.g. Invoice, Contract, Medical Record, Letter>",
              "summary": "<2-3 sentence summary of the document>",
              "keyTopics": ["<topic1>", "<topic2>", "<topic3>"]
            }
            Respond ONLY with the JSON object. No markdown, no explanation.
            Document content:
            """;

    private static final String CHUNK_PROMPT_TEMPLATE = """
            You are analyzing part %d of %d of a longer document. Provide a brief partial analysis in JSON with this exact structure:
            {
              "documentType": "<your best guess based on this part>",
              "summary": "<1-2 sentence summary of THIS PART only>",
              "keyTopics": ["<topic1>", "<topic2>"]
            }
            Respond ONLY with the JSON object. No markdown, no explanation.
            Part content:
            """;

    private static final String MERGE_PROMPT = """
            Below are partial JSON analyses of consecutive parts of a single document. Produce a final consolidated analysis in JSON with this exact structure:
            {
              "documentType": "<best document type given all parts>",
              "summary": "<2-3 sentence summary covering the whole document>",
              "keyTopics": ["<topic1>", "<topic2>", "<topic3>"]
            }
            Pick the most consistent documentType, union the keyTopics (dedup, keep the top ~5), and write a fresh summary covering the whole document — do not just concatenate the partial summaries.
            Respond ONLY with the JSON object. No markdown, no explanation.
            Partial analyses:
            """;

    private final String provider;
    private final WebClient ollamaClient;
    private final WebClient anthropicClient;
    private final String ollamaModel;
    private final String ollamaTextModel;
    private final String anthropicModel;

    public LlmService(
            @Value("${app.ai.provider}") String provider,
            @Value("${app.ai.connect-timeout}") Duration connectTimeout,
            @Value("${app.ai.response-timeout}") Duration responseTimeout,
            @Value("${app.ai.ollama.base-url}") String ollamaBaseUrl,
            @Value("${app.ai.ollama.model}") String ollamaModel,
            @Value("${app.ai.ollama.text-model}") String ollamaTextModel,
            @Value("${app.ai.anthropic.base-url}") String anthropicBaseUrl,
            @Value("${app.ai.anthropic.api-key}") String anthropicApiKey,
            @Value("${app.ai.anthropic.model}") String anthropicModel
    ) {
        this.provider = provider;
        this.ollamaModel = ollamaModel;
        this.ollamaTextModel = ollamaTextModel;
        this.anthropicModel = anthropicModel;

        ReactorClientHttpConnector connector = timeoutConnector(connectTimeout, responseTimeout);

        this.ollamaClient = WebClient.builder()
                .baseUrl(ollamaBaseUrl)
                .clientConnector(connector)
                .build();

        this.anthropicClient = WebClient.builder()
                .baseUrl(anthropicBaseUrl)
                .defaultHeader("x-api-key", anthropicApiKey)
                .defaultHeader("anthropic-version", "2023-06-01")
                .clientConnector(connector)
                .build();
    }

    private static ReactorClientHttpConnector timeoutConnector(Duration connectTimeout, Duration responseTimeout) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) connectTimeout.toMillis())
                .responseTimeout(responseTimeout);
        return new ReactorClientHttpConnector(httpClient);
    }

    /**
     * Analyze extracted text from a PDF in a single call. Use this when the
     * full text fits comfortably in the model's context.
     */
    public String analyzeText(String extractedText) {
        return callTextProvider(ANALYSIS_PROMPT + "\n\n" + extractedText);
    }

    /**
     * Analyze one chunk of a longer document. Tells the model it's looking
     * at part {@code chunkIndex} of {@code chunkTotal} so the partial summary
     * is appropriately scoped.
     */
    public String analyzeTextChunk(String chunkText, int chunkIndex, int chunkTotal) {
        String prompt = String.format(CHUNK_PROMPT_TEMPLATE, chunkIndex, chunkTotal) + "\n\n" + chunkText;
        return callTextProvider(prompt);
    }

    /**
     * Consolidate the partial JSON analyses of a chunked document into a
     * single final JSON analysis.
     */
    public String mergePartialSummaries(List<String> partials) {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < partials.size(); i++) {
            body.append("--- Part ").append(i + 1).append(" ---\n")
                    .append(partials.get(i)).append("\n");
        }
        return callTextProvider(MERGE_PROMPT + "\n\n" + body);
    }

    /**
     * Analyze an image document (e.g. scanned JPG/PNG).
     * Ollama uses llava; Anthropic uses claude's vision API.
     */
    public String analyzeImage(byte[] imageBytes, String mimeType) {
        String base64 = Base64.getEncoder().encodeToString(imageBytes);
        return switch (provider) {
            case "anthropic" -> callAnthropicVision(base64, mimeType);
            default -> callOllamaVision(base64);
        };
    }

    private String callTextProvider(String prompt) {
        return switch (provider) {
            case "anthropic" -> callAnthropic(prompt);
            default -> callOllamaText(prompt);
        };
    }

    // ── Ollama ───────────────────────────────────────────────────────────────

    private String callOllamaText(String prompt) {
        log.debug("Calling Ollama text model: {}", ollamaTextModel);

        Map<String, Object> body = Map.of(
                "model", ollamaTextModel,
                "prompt", prompt,
                "stream", false
        );

        var response = ollamaClient.post()
                .uri("/api/generate")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Map.class)
                .block();

        return response != null ? (String) response.get("response") : "";
    }

    private String callOllamaVision(String base64Image) {
        log.debug("Calling Ollama vision model: {}", ollamaModel);

        Map<String, Object> body = Map.of(
                "model", ollamaModel,
                "prompt", ANALYSIS_PROMPT,
                "images", List.of(base64Image),
                "stream", false
        );

        var response = ollamaClient.post()
                .uri("/api/generate")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Map.class)
                .block();

        return response != null ? (String) response.get("response") : "";
    }

    // ── Anthropic ────────────────────────────────────────────────────────────

    private String callAnthropic(String prompt) {
        log.debug("Calling Anthropic model: {}", anthropicModel);

        Map<String, Object> body = Map.of(
                "model", anthropicModel,
                "max_tokens", 1024,
                "messages", List.of(Map.of(
                        "role", "user",
                        "content", prompt
                ))
        );

        var response = anthropicClient.post()
                .uri("/v1/messages")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Map.class)
                .block();

        return extractAnthropicText(response);
    }

    private String callAnthropicVision(String base64Image, String mimeType) {
        log.debug("Calling Anthropic vision: {}", anthropicModel);

        Map<String, Object> body = Map.of(
                "model", anthropicModel,
                "max_tokens", 1024,
                "messages", List.of(Map.of(
                        "role", "user",
                        "content", List.of(
                                Map.of(
                                        "type", "image",
                                        "source", Map.of(
                                                "type", "base64",
                                                "media_type", mimeType,
                                                "data", base64Image
                                        )
                                ),
                                Map.of("type", "text", "text", ANALYSIS_PROMPT)
                        )
                ))
        );

        var response = anthropicClient.post()
                .uri("/v1/messages")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Map.class)
                .block();

        return extractAnthropicText(response);
    }

    /**
     * Anthropic responses contain a list of typed content blocks
     * ({@code text}, {@code tool_use}, etc). Return the first {@code text}
     * block's value, or an empty string if none is present. Package-private
     * so it can be unit tested without spinning up a WebClient.
     */
    @SuppressWarnings("unchecked")
    static String extractAnthropicText(Map<?, ?> response) {
        if (response == null) return "";
        Object raw = response.get("content");
        if (!(raw instanceof List<?> content)) return "";
        return content.stream()
                .filter(Map.class::isInstance)
                .map(block -> (Map<String, Object>) block)
                .filter(block -> "text".equals(block.get("type")))
                .map(block -> block.get("text"))
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse("");
    }
}
