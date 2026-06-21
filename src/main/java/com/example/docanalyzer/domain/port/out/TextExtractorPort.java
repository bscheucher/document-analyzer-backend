package com.example.docanalyzer.domain.port.out;

/**
 * Outbound port for extracting embedded text from a stored document. Implemented
 * by an adapter in {@code integration.extraction} (PDFBox). Framework-free.
 */
public interface TextExtractorPort {

    /**
     * Returns the embedded text of {@code content}, or {@code ""} when the given
     * {@code contentType} carries no extractable text (e.g. images, which are
     * handled by the vision model instead).
     */
    String extractText(byte[] content, String contentType);
}
