package com.example.docanalyzer.domain.service;

import com.example.docanalyzer.domain.model.AnalysisResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * Interprets a raw LLM response (which may be wrapped in prose or markdown
 * fences) and applies the extracted fields to an {@link AnalysisResult}. Uses
 * Jackson, which is a plain library — the domain stays free of frameworks.
 */
@Slf4j
public class LlmResponseParser {

    private final ObjectMapper objectMapper;

    public LlmResponseParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Finds the first JSON object carrying any expected field and copies
     * summary/documentType/keyTopics onto {@code result}. If none is found, the
     * raw text is stored as the summary.
     */
    public void applyTo(String raw, AnalysisResult result) {
        for (String candidate : extractJsonCandidates(raw)) {
            try {
                JsonNode json = objectMapper.readTree(candidate);
                if (!hasAnyExpectedField(json)) continue;

                result.setSummary(json.path("summary").asText());
                result.setDocumentType(json.path("documentType").asText());

                JsonNode topics = json.path("keyTopics");
                if (topics.isArray()) {
                    List<String> topicList = objectMapper.convertValue(
                            topics, objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
                    result.setKeyTopics(topicList);
                }
                return;
            } catch (Exception ignored) {
                // not parseable as JSON — try the next candidate
            }
        }
        log.warn("No usable JSON object found in LLM response, storing raw text.");
        result.setSummary(raw);
    }

    private static boolean hasAnyExpectedField(JsonNode json) {
        return json.has("summary") || json.has("documentType") || json.has("keyTopics");
    }

    /**
     * Returns every depth-balanced {...} substring in the input, in order.
     * String literals are skipped so braces inside quoted values don't
     * mis-balance the scan.
     */
    private static List<String> extractJsonCandidates(String raw) {
        if (raw == null || raw.isEmpty()) return List.of();
        List<String> candidates = new ArrayList<>();
        int depth = 0;
        int start = -1;
        boolean inString = false;
        boolean escaped = false;

        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);

            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }

            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                if (depth == 0) start = i;
                depth++;
            } else if (c == '}' && depth > 0) {
                depth--;
                if (depth == 0 && start >= 0) {
                    candidates.add(raw.substring(start, i + 1));
                    start = -1;
                }
            }
        }
        return candidates;
    }
}
