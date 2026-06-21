package com.example.docanalyzer.domain.port.out;

import java.util.List;

/**
 * Outbound port for the language model. Implemented by an adapter in
 * {@code integration.llm} (Ollama / Anthropic). Framework-free.
 */
public interface LlmPort {

    /** Analyze the full extracted text of a document in a single call. */
    String analyzeText(String extractedText);

    /** Analyze one chunk of a longer document (part {@code chunkIndex} of {@code chunkTotal}). */
    String analyzeTextChunk(String chunkText, int chunkIndex, int chunkTotal);

    /** Consolidate the partial JSON analyses of a chunked document into one. */
    String mergePartialSummaries(List<String> partials);

    /** Analyze an image document via the provider's vision API. */
    String analyzeImage(byte[] imageBytes, String mimeType);
}
