package com.example.docanalyzer.web.dto;

import java.util.List;

public record AnalysisResultDto(
        String summary,
        String documentType,
        List<String> keyTopics,
        String extractedText,
        String errorMessage
) {}
