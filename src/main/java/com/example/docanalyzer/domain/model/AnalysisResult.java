package com.example.docanalyzer.domain.model;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Framework-free domain model for the analysis of a {@link Document}. The JPA
 * mapping lives in {@code persistence.entity.AnalysisResultEntity}.
 */
@Getter
@Setter
public class AnalysisResult {
    private UUID id;
    private String summary;
    private String documentType;
    private List<String> keyTopics;
    private String extractedText;
    private String rawLlmResponse;
    private String errorMessage;
    private Instant createdAt;
}
