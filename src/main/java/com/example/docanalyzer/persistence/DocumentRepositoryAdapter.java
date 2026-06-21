package com.example.docanalyzer.persistence;

import com.example.docanalyzer.domain.model.AnalysisResult;
import com.example.docanalyzer.domain.model.Document;
import com.example.docanalyzer.domain.model.DocumentStatus;
import com.example.docanalyzer.domain.port.out.DocumentRepositoryPort;
import com.example.docanalyzer.persistence.entity.DocumentEntity;
import com.example.docanalyzer.persistence.mapper.PersistenceMapper;
import com.example.docanalyzer.persistence.repository.DocumentJpaRepository;
import com.example.docanalyzer.persistence.repository.UserJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link DocumentRepositoryPort} backed by Spring Data JPA. Owns the
 * transaction boundaries for the document aggregate; the write methods rely on
 * Hibernate dirty-checking within {@code @Transactional} just as the previous
 * persistence service did.
 */
@Service
@RequiredArgsConstructor
public class DocumentRepositoryAdapter implements DocumentRepositoryPort {

    private final DocumentJpaRepository documents;
    private final UserJpaRepository users;
    private final PersistenceMapper mapper;

    @Override
    @Transactional
    public Document save(Document document) {
        DocumentEntity entity = new DocumentEntity();
        // getReferenceById gives a lazy proxy — no extra SELECT for the owner.
        entity.setOwner(users.getReferenceById(document.getOwner().getId()));
        entity.setFilename(document.getFilename());
        entity.setFileType(document.getFileType());
        entity.setFileSize(document.getFileSize());
        entity.setStoragePath(document.getStoragePath());
        entity.setContentType(document.getContentType());
        entity.setStatus(document.getStatus());
        return mapper.toDomain(documents.save(entity));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Document> findById(UUID id) {
        return documents.findById(id).map(mapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Document> findByIdAndOwner(UUID id, UUID ownerId) {
        return documents.findByIdAndOwner(id, ownerId).map(mapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Document> findByIdAndOwnerWithResult(UUID id, UUID ownerId) {
        return documents.findByIdAndOwnerWithResult(id, ownerId).map(mapper::toDomainWithResult);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Document> findAllByOwnerWithResults(UUID ownerId) {
        return documents.findAllByOwnerWithResults(ownerId).stream()
                .map(mapper::toDomainWithResult)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Document loadWithResult(UUID id) {
        // Unscoped lookup — only called from the async analysis pipeline,
        // which was already authorised by the controller that scheduled it.
        DocumentEntity entity = documents.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Document not found: " + id));
        return mapper.toDomain(entity);
    }

    @Override
    @Transactional
    public void updateStatus(UUID id, DocumentStatus status) {
        DocumentEntity doc = documents.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Document not found: " + id));
        doc.setStatus(status);
    }

    @Override
    @Transactional
    public void completeAnalysis(UUID id, AnalysisResult result) {
        DocumentEntity doc = documents.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Document not found: " + id));
        doc.setStatus(DocumentStatus.DONE);
        doc.setAnalysisResult(mapper.toEntity(result, doc));
    }

    @Override
    @Transactional
    public void failAnalysis(UUID id, AnalysisResult result, String errorMessage) {
        DocumentEntity doc = documents.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Document not found: " + id));
        doc.setStatus(DocumentStatus.FAILED);
        result.setErrorMessage(errorMessage);
        doc.setAnalysisResult(mapper.toEntity(result, doc));
    }

    @Override
    @Transactional
    public String deleteAndReturnPath(UUID id, UUID ownerId) {
        DocumentEntity doc = documents.findByIdAndOwner(id, ownerId).orElse(null);
        if (doc == null) return null;
        String storagePath = doc.getStoragePath();
        documents.delete(doc);
        return storagePath;
    }
}
