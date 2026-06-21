package com.example.docanalyzer.dto;

import com.example.docanalyzer.domain.model.DocumentStatus;
import com.example.docanalyzer.domain.model.FileType;

import java.time.Instant;
import java.util.UUID;

public record DocumentUploadResponse(
        UUID id,
        String filename,
        FileType fileType,
        Long fileSize,
        DocumentStatus status,
        Instant createdAt
) {}
