package com.example.docanalyzer.domain.service;

import com.example.docanalyzer.domain.model.AnalysisProgress;
import com.example.docanalyzer.domain.model.AnalysisResult;
import com.example.docanalyzer.domain.model.Document;
import com.example.docanalyzer.domain.model.DocumentStatus;
import com.example.docanalyzer.domain.model.FileType;
import com.example.docanalyzer.domain.port.in.DocumentAnalysisUseCase;
import com.example.docanalyzer.domain.port.in.UploadCommand;
import com.example.docanalyzer.domain.port.out.DocumentRepositoryPort;
import com.example.docanalyzer.domain.port.out.LlmPort;
import com.example.docanalyzer.domain.port.out.ProgressNotifier;
import com.example.docanalyzer.domain.port.out.StoragePort;
import com.example.docanalyzer.domain.port.out.TextExtractorPort;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Framework-free orchestration of the document analysis pipeline. Collaborates
 * with the outside world only through ports; the {@code @Async} hop and SSE
 * lifecycle live in the web layer. Wired as a bean by {@code config.DomainConfig}.
 */
@Slf4j
public class DocumentAnalysisService implements DocumentAnalysisUseCase {

    private final DocumentRepositoryPort documentRepository;
    private final StoragePort storageService;
    private final LlmPort llmService;
    private final TextExtractorPort textExtractor;
    private final ProgressNotifier progressNotifier;
    private final ChunkingConfig chunking;
    private final LlmResponseParser responseParser;

    public DocumentAnalysisService(
            DocumentRepositoryPort documentRepository,
            StoragePort storageService,
            LlmPort llmService,
            TextExtractorPort textExtractor,
            ProgressNotifier progressNotifier,
            ChunkingConfig chunking,
            LlmResponseParser responseParser) {
        this.documentRepository = documentRepository;
        this.storageService = storageService;
        this.llmService = llmService;
        this.textExtractor = textExtractor;
        this.progressNotifier = progressNotifier;
        this.chunking = chunking;
        this.responseParser = responseParser;
    }

    // ── Upload ───────────────────────────────────────────────────────────────

    @Override
    public Document upload(UploadCommand command) throws IOException {
        String storagePath;
        try (InputStream content = command.content()) {
            storagePath = storageService.store(content, command.filename());
        }
        FileType fileType = detectFileType(command.contentType());

        Document doc = new Document();
        doc.setOwner(command.owner());
        doc.setFilename(command.filename());
        doc.setFileType(fileType);
        doc.setFileSize(command.size());
        doc.setStoragePath(storagePath);
        doc.setContentType(command.contentType());
        doc.setStatus(DocumentStatus.PENDING);

        return documentRepository.save(doc);
    }

    // ── Delete ───────────────────────────────────────────────────────────────

    @Override
    public boolean delete(UUID documentId, UUID ownerId) {
        String storagePath = documentRepository.deleteAndReturnPath(documentId, ownerId);
        if (storagePath == null) return false;

        try {
            storageService.delete(storagePath);
        } catch (IOException e) {
            log.warn("Failed to delete stored file {} for document {}", storagePath, documentId, e);
        }
        return true;
    }

    // ── Analysis pipeline (synchronous; async hop is in the web layer) ─────────

    @Override
    public void analyze(UUID documentId) {
        Document doc = documentRepository.loadWithResult(documentId);
        AnalysisResult result = new AnalysisResult();

        try {
            progressNotifier.publish(documentId, new AnalysisProgress("EXTRACTING", "Extracting text...", 10));
            documentRepository.updateStatus(documentId, DocumentStatus.EXTRACTING);

            String extractedText = extractText(doc);
            result.setExtractedText(extractedText);
            progressNotifier.publish(documentId, new AnalysisProgress("EXTRACTING",
                    doc.getFileType() == FileType.PDF
                            ? "Text extracted successfully."
                            : "Image ready for vision model.", 30));

            progressNotifier.publish(documentId, new AnalysisProgress("ANALYZING", "Sending to AI model...", 50));
            documentRepository.updateStatus(documentId, DocumentStatus.ANALYZING);

            String llmResponse = doc.getFileType() == FileType.IMAGE
                    ? llmService.analyzeImage(
                            storageService.readBytes(doc.getStoragePath()),
                            doc.getContentType() != null ? doc.getContentType() : "image/jpeg")
                    : analyzeTextMaybeChunked(documentId, extractedText);

            result.setRawLlmResponse(llmResponse);
            responseParser.applyTo(llmResponse, result);

            documentRepository.completeAnalysis(documentId, result);
            progressNotifier.publish(documentId, new AnalysisProgress("DONE", "Analysis complete!", 100));

        } catch (Exception e) {
            log.error("Analysis failed for document {}", documentId, e);
            documentRepository.failAnalysis(documentId, result, e.getMessage());
            progressNotifier.publish(documentId, new AnalysisProgress("FAILED", "Analysis failed: " + e.getMessage(), 100));
        } finally {
            progressNotifier.complete(documentId);
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private String extractText(Document doc) throws IOException {
        if (doc.getFileType() == FileType.PDF) {
            byte[] content = storageService.readBytes(doc.getStoragePath());
            return textExtractor.extractText(content, doc.getContentType());
        }
        return "";
    }

    /**
     * Single LLM call when the extracted text fits comfortably, otherwise
     * chunked map-reduce: summarise each chunk, then ask the model to
     * consolidate the partials into a final analysis.
     */
    String analyzeTextMaybeChunked(UUID documentId, String text) {
        if (text == null || text.length() <= chunking.thresholdChars()) {
            return llmService.analyzeText(text == null ? "" : text);
        }

        List<String> chunks = TextChunker.chunk(text, chunking.chunkSizeChars(), chunking.overlapChars());
        log.info("Chunked document {} into {} chunks ({} chars)",
                documentId, chunks.size(), text.length());

        List<String> partials = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            progressNotifier.publish(documentId, new AnalysisProgress("ANALYZING",
                    String.format("Analyzing chunk %d of %d...", i + 1, chunks.size()),
                    50 + (40 * (i + 1)) / (chunks.size() + 1)));
            partials.add(llmService.analyzeTextChunk(chunks.get(i), i + 1, chunks.size()));
        }

        progressNotifier.publish(documentId, new AnalysisProgress("ANALYZING",
                "Consolidating chunk summaries...", 90));
        return llmService.mergePartialSummaries(partials);
    }

    private FileType detectFileType(String contentType) {
        if (contentType == null) return FileType.IMAGE;
        return contentType.equalsIgnoreCase("application/pdf")
                ? FileType.PDF
                : FileType.IMAGE;
    }
}
