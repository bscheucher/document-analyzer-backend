package com.example.docanalyzer.persistence.mapper;

import com.example.docanalyzer.domain.model.AnalysisResult;
import com.example.docanalyzer.domain.model.Document;
import com.example.docanalyzer.domain.model.User;
import com.example.docanalyzer.persistence.entity.AnalysisResultEntity;
import com.example.docanalyzer.persistence.entity.DocumentEntity;
import com.example.docanalyzer.persistence.entity.UserEntity;
import org.springframework.stereotype.Component;

/**
 * Translates between JPA entities and the framework-free domain model. The only
 * class that knows both shapes. All entity-graph navigation here happens inside
 * the calling adapter's transaction.
 */
@Component
public class PersistenceMapper {

    public User toDomain(UserEntity entity) {
        if (entity == null) return null;
        User user = new User();
        user.setId(entity.getId());
        user.setEmail(entity.getEmail());
        user.setCreatedAt(entity.getCreatedAt());
        return user;
    }

    /** Maps a document without its analysis result. */
    public Document toDomain(DocumentEntity entity) {
        return map(entity, false);
    }

    /** Maps a document including its (already-fetched) analysis result. */
    public Document toDomainWithResult(DocumentEntity entity) {
        return map(entity, true);
    }

    private Document map(DocumentEntity entity, boolean withResult) {
        if (entity == null) return null;
        Document doc = new Document();
        doc.setId(entity.getId());
        if (entity.getOwner() != null) {
            // Calling getId() on a lazy owner proxy does not trigger
            // initialization — the id is already known. We deliberately map a
            // minimal owner (id only) so read paths never touch the proxy.
            User owner = new User();
            owner.setId(entity.getOwner().getId());
            doc.setOwner(owner);
        }
        doc.setFilename(entity.getFilename());
        doc.setFileType(entity.getFileType());
        doc.setFileSize(entity.getFileSize());
        doc.setStoragePath(entity.getStoragePath());
        doc.setContentType(entity.getContentType());
        doc.setStatus(entity.getStatus());
        doc.setCreatedAt(entity.getCreatedAt());
        doc.setUpdatedAt(entity.getUpdatedAt());
        if (withResult) {
            doc.setAnalysisResult(toDomain(entity.getAnalysisResult()));
        }
        return doc;
    }

    public AnalysisResult toDomain(AnalysisResultEntity entity) {
        if (entity == null) return null;
        AnalysisResult result = new AnalysisResult();
        result.setId(entity.getId());
        result.setSummary(entity.getSummary());
        result.setDocumentType(entity.getDocumentType());
        result.setKeyTopics(entity.getKeyTopics());
        result.setExtractedText(entity.getExtractedText());
        result.setRawLlmResponse(entity.getRawLlmResponse());
        result.setErrorMessage(entity.getErrorMessage());
        result.setCreatedAt(entity.getCreatedAt());
        return result;
    }

    /**
     * Builds a new result entity from the domain result, linked to its owning
     * document entity. Used when persisting a completed/failed analysis.
     */
    public AnalysisResultEntity toEntity(AnalysisResult result, DocumentEntity document) {
        AnalysisResultEntity entity = new AnalysisResultEntity();
        entity.setDocument(document);
        entity.setSummary(result.getSummary());
        entity.setDocumentType(result.getDocumentType());
        entity.setKeyTopics(result.getKeyTopics());
        entity.setExtractedText(result.getExtractedText());
        entity.setRawLlmResponse(result.getRawLlmResponse());
        entity.setErrorMessage(result.getErrorMessage());
        return entity;
    }
}
