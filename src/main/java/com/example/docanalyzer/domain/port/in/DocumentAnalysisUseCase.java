package com.example.docanalyzer.domain.port.in;

import com.example.docanalyzer.domain.model.Document;

import java.io.IOException;
import java.util.UUID;

/**
 * Inbound port: the document analysis lifecycle as the web layer drives it.
 * Implemented by {@code domain.service.DocumentAnalysisService}.
 */
public interface DocumentAnalysisUseCase {

    /** Stores the uploaded file and persists a PENDING document. */
    Document upload(UploadCommand command) throws IOException;

    /**
     * Runs the full extract → analyze → persist pipeline synchronously,
     * emitting progress along the way. The async hop lives in the web layer.
     */
    void analyze(UUID documentId);

    /** Deletes the owner's document and its stored blob. */
    boolean delete(UUID documentId, UUID ownerId);
}
