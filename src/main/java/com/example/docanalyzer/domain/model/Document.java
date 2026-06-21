package com.example.docanalyzer.domain.model;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Framework-free domain model for an uploaded document and its analysis
 * lifecycle. The JPA mapping lives in {@code persistence.entity.DocumentEntity};
 * {@code PersistenceMapper} translates between the two.
 */
@Getter
@Setter
public class Document {
    private UUID id;
    private User owner;
    private String filename;
    private FileType fileType;
    private Long fileSize;
    private String storagePath;
    private String contentType;
    private DocumentStatus status;
    private Instant createdAt;
    private Instant updatedAt;
    private AnalysisResult analysisResult;
}
