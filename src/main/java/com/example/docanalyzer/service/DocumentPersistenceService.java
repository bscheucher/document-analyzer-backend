package com.example.docanalyzer.service;

import com.example.docanalyzer.entity.AnalysisResult;
import com.example.docanalyzer.entity.Document;
import com.example.docanalyzer.repository.DocumentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DocumentPersistenceService {

    private final DocumentRepository documentRepository;

    @Transactional(readOnly = true)
    public Document loadWithResult(UUID id) {
        // Unscoped lookup — only called from the async analysis pipeline,
        // which was already authorised by the controller that scheduled it.
        return documentRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Document not found: " + id));
    }

    @Transactional
    public void updateStatus(UUID id, Document.DocumentStatus status) {
        Document doc = documentRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Document not found: " + id));
        doc.setStatus(status);
    }

    @Transactional
    public void completeAnalysis(UUID id, AnalysisResult result) {
        Document doc = documentRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Document not found: " + id));
        doc.setStatus(Document.DocumentStatus.DONE);
        result.setDocument(doc);
        doc.setAnalysisResult(result);
    }

    @Transactional
    public void failAnalysis(UUID id, AnalysisResult result, String errorMessage) {
        Document doc = documentRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Document not found: " + id));
        doc.setStatus(Document.DocumentStatus.FAILED);
        result.setDocument(doc);
        result.setErrorMessage(errorMessage);
        doc.setAnalysisResult(result);
    }

    @Transactional
    public String deleteAndReturnPath(UUID id, UUID ownerId) {
        Document doc = documentRepository.findByIdAndOwner(id, ownerId).orElse(null);
        if (doc == null) return null;
        String storagePath = doc.getStoragePath();
        documentRepository.delete(doc);
        return storagePath;
    }
}
