package com.example.docanalyzer.domain.port.out;

import com.example.docanalyzer.domain.model.AnalysisResult;
import com.example.docanalyzer.domain.model.Document;
import com.example.docanalyzer.domain.model.DocumentStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Outbound port for document persistence. Implemented by
 * {@code persistence.DocumentRepositoryAdapter}, which owns the transaction
 * boundaries. Speaks only the framework-free domain model.
 */
public interface DocumentRepositoryPort {

    /** Persists a new document and returns it with generated id/timestamps. */
    Document save(Document document);

    /** Unscoped lookup (no analysis result). Used by the analysis pipeline. */
    Optional<Document> findById(UUID id);

    /** Owner-scoped lookup without the analysis result. */
    Optional<Document> findByIdAndOwner(UUID id, UUID ownerId);

    /** Owner-scoped lookup with the analysis result eagerly loaded. */
    Optional<Document> findByIdAndOwnerWithResult(UUID id, UUID ownerId);

    /** The owner's documents (latest first), each with its analysis result. */
    List<Document> findAllByOwnerWithResults(UUID ownerId);

    /** Unscoped load for the analysis pipeline; throws if the id is unknown. */
    Document load(UUID id);

    void updateStatus(UUID id, DocumentStatus status);

    void completeAnalysis(UUID id, AnalysisResult result);

    void failAnalysis(UUID id, AnalysisResult result, String errorMessage);

    /**
     * Deletes the owner's document and returns its storage key (so the caller
     * can remove the blob), or {@code null} if no such document exists.
     */
    String deleteAndReturnPath(UUID id, UUID ownerId);
}
