package com.example.docanalyzer.dto;

import com.example.docanalyzer.entity.Document;

import java.time.Instant;
import java.util.UUID;

public record DocumentDetailResponse(
        UUID id,
        String filename,
        Document.FileType fileType,
        Long fileSize,
        Document.DocumentStatus status,
        Instant createdAt,
        AnalysisResultDto analysisResult
) {}
