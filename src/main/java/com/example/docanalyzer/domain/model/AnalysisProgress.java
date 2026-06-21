package com.example.docanalyzer.domain.model;

/**
 * Framework-free progress signal emitted by the analysis pipeline. The web
 * layer adapts this to the SSE wire format ({@code AnalysisProgressEvent}).
 */
public record AnalysisProgress(
        String stage,        // "EXTRACTING" | "ANALYZING" | "DONE" | "FAILED"
        String message,
        int progressPercent  // 0–100
) {}
