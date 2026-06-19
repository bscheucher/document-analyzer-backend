package com.example.docanalyzer.dto;

public record AnalysisProgressEvent(
        String stage,        // "EXTRACTING" | "ANALYZING" | "DONE" | "FAILED"
        String message,
        int progressPercent  // 0–100
) {}
